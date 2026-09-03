import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

/** Non-blocking reactor: selector ownership stays on one thread; workers return immutable completions. */
public final class NioHttpServer {
    public static final int MAX_BODY_SIZE = 10 * 1024 * 1024;
    private static final int MAX_HEADER_SIZE = 16 * 1024;
    private static final int MAX_ACTIVE_CONNECTIONS = 10_000;
    private static final int MAX_REQUESTS_PER_CONNECTION = 100;
    private static final long IDLE_TIMEOUT_MS = 15_000;
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;
    private static final int WORKER_THREADS = 10;
    private static final int MAX_QUEUED_REQUESTS = 1_000;
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS), new ThreadPoolExecutor.AbortPolicy());
    private static volatile boolean shutdownRequested;

    private NioHttpServer() { }

    @SuppressWarnings("try") // server is closed early during graceful shutdown, then again by try-with-resources.
    public static void main(String[] args) {
        int port = args.length == 1 ? Integer.parseInt(args[0]) : 8080;
        ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
        ServerState serverState = new ServerState();
        Router router = routes();
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));
            server.register(selector, SelectionKey.OP_ACCEPT);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { shutdownRequested = true; selector.wakeup(); }, "nio-shutdown"));
            System.out.println("NIO HTTP server listening on port " + port);
            long shutdownAt = 0;
            while (true) {
                selector.select(1_000);
                if (shutdownRequested && shutdownAt == 0) {
                    shutdownAt = System.currentTimeMillis();
                    server.close();                 // stop accepts before stopping submissions
                    WORKERS.shutdown();             // already submitted work may still finish
                }
                installCompletions(completions);
                selected(selector, router, completions, serverState);
                expire(selector, shutdownAt, serverState);
                if (shutdownAt != 0 && (serverState.activeConnections == 0 || System.currentTimeMillis() - shutdownAt >= SHUTDOWN_TIMEOUT_MS)) break;
            }
        } catch (IOException e) {
            if (!shutdownRequested) System.err.println("Fatal NIO error: " + e.getMessage());
        } finally {
            WORKERS.shutdownNow();
            try { WORKERS.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void selected(Selector selector, Router router, ConcurrentLinkedQueue<Completion> completions, ServerState serverState) {
        Set<SelectionKey> keys = selector.selectedKeys();
        for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
            SelectionKey key = it.next(); it.remove();
            if (!key.isValid()) continue;
            try {
                if (key.isAcceptable()) accept(key, selector, serverState);
                if (key.isValid() && key.isReadable()) read(key, router, completions, serverState);
                if (key.isValid() && key.isWritable()) write(key, router, completions, serverState);
            } catch (IOException e) { close(key, serverState); }
        }
    }

    private static void accept(SelectionKey key, Selector selector, ServerState serverState) throws IOException {
        SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
        if (channel == null) return;
        if (shutdownRequested || serverState.activeConnections >= MAX_ACTIVE_CONNECTIONS) { channel.close(); return; }
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, new ClientState());
        serverState.activeConnections++;
    }

    private static void read(SelectionKey key, Router router, ConcurrentLinkedQueue<Completion> completions, ServerState serverState) throws IOException {
        ClientState state = (ClientState) key.attachment();
        if (state.processing || state.writeBuffer != null) return;
        int count = ((SocketChannel) key.channel()).read(state.readBuffer);
        if (count < 0) { close(key, serverState); return; }
        if (count == 0) return;
        state.updateActivity();
        state.readBuffer.flip();
        byte[] chunk = new byte[state.readBuffer.remaining()];
        state.readBuffer.get(chunk); state.readBuffer.clear();
        state.accumulator.write(chunk);
        process(key, state, router, completions);
        if (!state.processing && state.writeBuffer != null) key.interestOps(SelectionKey.OP_WRITE);
    }

    private static void process(SelectionKey key, ClientState state, Router router, ConcurrentLinkedQueue<Completion> completions) {
        byte[] bytes = state.accumulator.toByteArray();
        if (!state.headersParsed) {
            int boundary = headerBoundary(bytes);
            if (boundary < 0) { if (bytes.length > MAX_HEADER_SIZE) error(key, state, 431, "Request Header Fields Too Large"); return; }
            if (boundary > MAX_HEADER_SIZE) { error(key, state, 431, "Request Header Fields Too Large"); return; }
            state.headersParsed = true; state.boundaryIndex = boundary;
            try { state.contentLength = contentLength(bytes, boundary); }
            catch (PayloadTooLargeException e) { error(key, state, 413, "Payload Too Large"); return; }
            catch (IllegalArgumentException e) { error(key, state, 400, "Bad Request"); return; }
        }
        int total = state.boundaryIndex + 4 + state.contentLength;
        if (bytes.length < total) return;
        byte[] requestBytes = Arrays.copyOfRange(bytes, 0, total);
        byte[] leftovers = Arrays.copyOfRange(bytes, total, bytes.length);
        state.accumulator.reset(); state.accumulator.write(leftovers, 0, leftovers.length);
        state.processing = true;
        state.requestsProcessed++;
        final int requestNumber = state.requestsProcessed;
        key.interestOps(0);
        final int requestBoundary = state.boundaryIndex; // immutable per-task value
        try {
            WORKERS.submit(() -> {
                Completion completion;
                try {
                    HttpRequest request = new HttpParser().parseRequest(requestBytes, requestBoundary);
                    HttpResponse response = router.route(request);
                    boolean keepAlive = response.isKeepAlive() && request.isKeepAlive()
                            && requestNumber < MAX_REQUESTS_PER_CONNECTION && !shutdownRequested;
                    response.setKeepAlive(keepAlive);
                    completion = new Completion(key, request, response.toByteBuffer(), false);
                } catch (IllegalArgumentException e) {
                    completion = new Completion(key, null, HttpResponse.createErrorResponse(400, "Bad Request").toByteBuffer(), true);
                } catch (Exception e) {
                    completion = new Completion(key, null, HttpResponse.createErrorResponse(500, "Internal Server Error").toByteBuffer(), true);
                }
                completions.add(completion);
                key.selector().wakeup();
            });
        } catch (RejectedExecutionException e) {
            state.processing = false; error(key, state, 503, "Service Unavailable");
        }
    }

    private static void installCompletions(ConcurrentLinkedQueue<Completion> completions) {
        for (Completion completion; (completion = completions.poll()) != null;) {
            if (!completion.key.isValid() || !(completion.key.attachment() instanceof ClientState)) continue;
            ClientState state = (ClientState) completion.key.attachment();
            state.processing = false;
            state.request = completion.request;
            state.writeBuffer = completion.response;
            state.malformedRequest = completion.close;
            completion.key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private static void write(SelectionKey key, Router router, ConcurrentLinkedQueue<Completion> completions, ServerState serverState) throws IOException {
        ClientState state = (ClientState) key.attachment();
        if (state.writeBuffer == null) return;
        if (((SocketChannel) key.channel()).write(state.writeBuffer) > 0) state.updateActivity();
        if (state.writeBuffer.hasRemaining()) return;
        if (state.malformedRequest || state.request == null || !state.request.isKeepAlive() ||
                state.requestsProcessed >= MAX_REQUESTS_PER_CONNECTION || shutdownRequested) { close(key, serverState); return; }
        state.resetForNextRequest();
        key.interestOps(SelectionKey.OP_READ);
        if (state.accumulator.size() > 0) {
            process(key, state, router, completions);
            if (!state.processing && state.writeBuffer != null) key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private static void error(SelectionKey key, ClientState state, int code, String message) {
        state.malformedRequest = true;
        state.writeBuffer = HttpResponse.createErrorResponse(code, message).toByteBuffer();
        if (key.isValid()) key.interestOps(SelectionKey.OP_WRITE);
    }

    private static void expire(Selector selector, long shutdownAt, ServerState serverState) {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) if (key.attachment() instanceof ClientState && key.isValid()) {
            ClientState state = (ClientState) key.attachment();
            if ((shutdownAt != 0 && now - shutdownAt >= SHUTDOWN_TIMEOUT_MS) ||
                    (!state.processing && now - state.lastActivityTime > IDLE_TIMEOUT_MS)) close(key, serverState);
        }
    }

    private static void close(SelectionKey key, ServerState serverState) {
        if (key.attachment() instanceof ClientState state && !state.closed) {
            state.closed = true;
            if (serverState != null) serverState.activeConnections--;
        }
        try { key.channel().close(); } catch (IOException ignored) { }
        key.cancel();
    }
    private static int headerBoundary(byte[] bytes) { for (int i = 0; i <= bytes.length - 4; i++) if (bytes[i] == '\r' && bytes[i+1] == '\n' && bytes[i+2] == '\r' && bytes[i+3] == '\n') return i; return -1; }
    private static int contentLength(byte[] bytes, int boundary) {
        String[] lines = new String(bytes, 0, boundary, StandardCharsets.ISO_8859_1).split("\\r\\n"); int value = 0; boolean found = false;
        for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0 && "content-length".equalsIgnoreCase(lines[i].substring(0, colon).trim())) {
            if (found) throw new IllegalArgumentException();
            try {
                long parsed = Long.parseLong(lines[i].substring(colon + 1).trim());
                if (parsed < 0) throw new IllegalArgumentException();
                if (parsed > HttpParser.MAX_BODY_SIZE) throw new PayloadTooLargeException();
                value = (int) parsed;
            } catch (NumberFormatException e) { throw new IllegalArgumentException(); }
            found = true;
        }} return value;
    }
    private static Router routes() {
        Router router = new Router();
        router.addRoute("GET", "/", r -> response(200, "OK", "Home Page"));
        router.addRoute("GET", "/hello", r -> response(200, "OK", "Hello World"));
        router.addRoute("GET", "/about", r -> response(200, "OK", "About Page"));
        router.addRoute("POST", "/users", r -> response(201, "Created", "User creation endpoint"));
        router.addRoute("GET", "/crash", r -> { throw new RuntimeException("Deliberate crash"); });
        return router;
    }
    private static HttpResponse response(int code, String status, String body) { HttpResponse r = new HttpResponse(code, status); r.setBody(body); return r; }
    private record Completion(SelectionKey key, HttpRequest request, ByteBuffer response, boolean close) { }
    private static final class ServerState { private int activeConnections; }
    private static final class PayloadTooLargeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
    }
}
