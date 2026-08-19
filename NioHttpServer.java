import java.io.IOException;
import java.net.InetSocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;


public class NioHttpServer {
    
    // --- SECURITY & LIMIT CONSTANTS ---
    private static final int MAX_HEADER_SIZE = 16 * 1024;         // 16 KB limits memory exhaustion per request
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;    // 10 MB limits payload bombs
    
    // --- TIMEOUT & LIFECYCLE CONSTANTS ---
    private static final long CONNECTION_TIMEOUT_MS = 15000;      // 15 seconds max idle time
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MS = 5000; // 5 seconds max for shutdown
    
    private enum ServerState { RUNNING, SHUTTING_DOWN, TERMINATED }
    private static final int MAX_ACTIVE_CONNECTIONS = 10000;      // 1. Max concurrent active sockets
    private static final int MAX_REQUESTS_PER_CONNECTION = 100;   // 2. Max keep-alive loops per socket
    public int requestsProcessed = 0;
    // volatile ensures the shutdown hook thread and the reactor thread see the exact same value
    private static volatile ServerState serverState = ServerState.RUNNING;
    private static volatile long shutdownStartTime = 0;
    
    public static void main(String[] args) {
        int port = 8080;
        Router router = new Router();

        // --- Application Routes ---
        router.addRoute("GET", "/", (HttpRequest request) -> {
            HttpResponse res = new HttpResponse(200, "OK");
            res.setBody("Home Page");
            return res;
        });

        router.addRoute("GET", "/hello", (HttpRequest request) -> {
            HttpResponse res = new HttpResponse(200, "OK");
            res.setBody("Hello World!");
            return res;
        });

        router.addRoute("GET", "/about", (HttpRequest request) -> {
            HttpResponse res = new HttpResponse(200, "OK");
            res.setBody("About Page");
            return res;
        });

        router.addRoute("POST", "/users", (HttpRequest request) -> {
            HttpResponse res = new HttpResponse(201, "Created");
            res.setBody("User creation endpoint");
            return res;
        });

        router.addRoute("GET", "/crash", (HttpRequest request) -> {
            throw new RuntimeException("Deliberate crash for testing 500 Internal Server Error");
        });

        try {
            // 1. The Selector (The Traffic Cop)
            Selector selector = Selector.open();

            // 2. The Non-Blocking Server Socket
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));

            // CRITICAL: Mathematically prevents the thread from freezing during OS I/O
            serverChannel.configureBlocking(false);

            // 3. Registration
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("NIO Event Loop started on port " + port);

            // --- PHASE 5.7: JVM SHUTDOWN HOOK ---
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Shutdown] Signal received. Initiating graceful shutdown...");
                serverState = ServerState.SHUTTING_DOWN;
                shutdownStartTime = System.currentTimeMillis();
                
                // Wake up the reactor loop immediately from its 1000ms sleep
                selector.wakeup();
                
                // Block the JVM from exiting until the reactor loop finishes its cleanup
                while (serverState != ServerState.TERMINATED) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                System.out.println("[Shutdown] Clean exit complete.");
            }));

            // 4. The State-Aware Event Loop (The Reactor)
            while (serverState != ServerState.TERMINATED) {
                // select(1000) guarantees the thread wakes up every 1 second minimum
                selector.select(1000);
                
                // --- PHASE 5.7: STOP ACCEPTING NEW CONNECTIONS ---
                if (serverState == ServerState.SHUTTING_DOWN && serverChannel.isOpen()) {
                    System.out.println("[Shutdown] Closing server socket. Rejecting new TCP connections.");
                    serverChannel.close(); 
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) continue;

                    // Only accept connections if we are strictly RUNNING
                    if (key.isAcceptable() && serverState == ServerState.RUNNING) {
                        acceptConnection(key, selector);
                    }
                    if (key.isReadable()) {
                        handleRead(key, router);
                    }
                    if (key.isWritable()) {
                        handleWrite(key, router);
                    }
                }
                
                // --- PHASE 5.6 & 5.7: LIFECYCLE SWEEP ---
                // Runs exactly once per reactor cycle to enforce timeouts and graceful shutdowns
                manageLifecycle(selector);
            }
            
            // Final cleanup when the reactor terminates
            if (selector.isOpen()) {
                selector.close();
            }
            
        } catch (IOException e) {
            System.err.println("Fatal NIO error: " + e.getMessage());
        }
    }

    // --- PHASE 5.7: LIFECYCLE MANAGEMENT ---
    private static void manageLifecycle(Selector selector) {
        long now = System.currentTimeMillis();
        boolean isShuttingDown = (serverState == ServerState.SHUTTING_DOWN);
        boolean forceClose = isShuttingDown && (now - shutdownStartTime > GRACEFUL_SHUTDOWN_TIMEOUT_MS);
        int activeClients = 0;

        for (SelectionKey key : selector.keys()) {
            if (key.channel() instanceof ServerSocketChannel) continue;

            if (key.isValid() && key.attachment() instanceof ClientState) {
                ClientState state = (ClientState) key.attachment();
                SocketChannel channel = (SocketChannel) key.channel();
                    // 1. GRACEFUL SHUTDOWN HARD TIMEOUT: 5 seconds exceeded, kill everything instantly
                 if (forceClose) {
                    closeCleanly(key, channel);
                    continue;
                }

                // 2. GRACEFUL SHUTDOWN: Let active writers finish, kill idle readers
                if (isShuttingDown) {
                    if ((key.interestOps() & SelectionKey.OP_WRITE) != 0) {
                        activeClients++; // Client is actively writing a response, spare it
                    } else {
                        // Client is idle or just starting a read. Terminate it.
                        closeCleanly(key, channel);
                    }
                    continue;
                }

                // 3. PHASE 5.6: Normal Idle Timeout logic
                if (now - state.lastActivityTime > CONNECTION_TIMEOUT_MS) {
                    System.out.println("Connection timeout. Terminating idle socket.");
                    closeCleanly(key, channel);
                }
            }
        }

        // If we are shutting down and 0 active clients remain, release the JVM lock
        if (isShuttingDown && activeClients == 0) {
            System.out.println("[Shutdown] All clients finished. Terminating reactor.");
            serverState = ServerState.TERMINATED;
        }
    }

    private static void closeCleanly(SelectionKey key, SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {}
        key.cancel();
    }

    private static void acceptConnection(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        // --- 1. ENFORCE GLOBAL MAX ACTIVE CONNECTIONS ---
        // (Subtract 1 because the ServerSocketChannel itself occupies one key)
        if (selector.keys().size() - 1 >= MAX_ACTIVE_CONNECTIONS) {
            System.err.println("Security Alert: Max connections (" + MAX_ACTIVE_CONNECTIONS + ") reached. Dropping client.");
            clientChannel.close();
            return;
        }

        clientChannel.configureBlocking(false);
        clientChannel.register(selector, SelectionKey.OP_READ, new ClientState());

        System.out.println("Accepted non-blocking connection from " + clientChannel.getRemoteAddress());
    }

    private static void handleRead(SelectionKey key, Router router) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        try {
            int bytesRead = clientChannel.read(state.readBuffer);
            if (bytesRead == -1) {
                System.out.println("Client disconnected cleanly.");
                closeCleanly(key, clientChannel);
                return;
            }

            if (bytesRead == 0) {
                return;
            }
            
            // --- PHASE 5.6: REFRESH TIMEOUT ON READ ---
            state.updateActivity();
            
            // THE FLIP: Prepare buffer for reading data out of it
            state.readBuffer.flip();

            // Extract bytes
            byte[] chunk = new byte[state.readBuffer.remaining()];
            state.readBuffer.get(chunk);

            // Accumulate safely without complex ByteBuffer resizing math
            state.accumulator.write(chunk);

            // THE CLEAR: Reset buffer for the next OS read event
            state.readBuffer.clear();

            // Evaluate if we have a complete HTTP request ready to parse
            processAccumulator(key, state, clientChannel, router);

        } catch (IOException e) {
            System.err.println("Connection reset by peer.");
            closeCleanly(key, clientChannel);
        }
    }

    // Mathematically scans for the 4-byte HTTP header boundary sequence (\r\n\r\n)
    private static int findHeaderBoundary(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    // Extracts the integer value of Content-Length if it exists
    private static int extractContentLength(String headers) {
        String lowerHeaders = headers.toLowerCase();
        int idx = lowerHeaders.indexOf("content-length:");
        if (idx != -1) {
            int start = idx + 15;
            int end = lowerHeaders.indexOf("\r\n", start);
            if (end == -1)
                end = lowerHeaders.length();
            try {
                return Integer.parseInt(lowerHeaders.substring(start, end).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return 0;
    }

    private static void handleWrite(SelectionKey key, Router router) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();
         
        try {
            // --- PHASE 5.6: Capture bytes written to update activity ---
            int bytesWritten = clientChannel.write(state.writeBuffer);
            
            if (bytesWritten > 0) {
                state.updateActivity();
            }
            
            // Check for partial writes. Yield thread if network buffer is full.
            if (state.writeBuffer.hasRemaining()) {
                return; 
            }
            
            // --- PHASE 5.5 & 5.7: KEEP-ALIVE CHECK ---
            boolean keepAlive = state.request != null && state.request.isKeepAlive();
            
            // Force close if malformed OR if the server is shutting down
            if (state.malformedRequest || serverState == ServerState.SHUTTING_DOWN) {
                keepAlive = false;
            }

            if (keepAlive) {
                System.out.println("Response sent. Keeping connection alive for pipelined/future requests.");
                
                // Reset state parameters but leave leftover accumulator bytes intact.
                state.reset(); 

                // Check for pipelined requests already sitting in the accumulator
                if (state.accumulator.size() > 0) {
                    processAccumulator(key, state, clientChannel, router);
                    // Switch to OP_READ if we need more bytes for the pipelined request
                    if (!state.requestComplete) {
                        key.interestOps(SelectionKey.OP_READ);
                    }
                } else {
                    // No leftover bytes, wait for the next request cycle
                    key.interestOps(SelectionKey.OP_READ);
                }
            } else {
                System.out.println("Response sent. Connection: close.");
                closeCleanly(key, clientChannel);
            }
        } catch (IOException e) {
            System.err.println("Write failed: " + e.getMessage());
            closeCleanly(key, clientChannel);
        }
    }

    private static void processAccumulator(SelectionKey key, ClientState state, SocketChannel clientChannel, Router router) throws IOException {
        byte[] currentBytes = state.accumulator.toByteArray();

        // Step A: Hunt for the \r\n\r\n boundary and enforce MAX_HEADER_SIZE
        if (!state.headersParsed) {
            if (currentBytes.length > MAX_HEADER_SIZE) {
                System.err.println("Security Alert: Header size exceeded 16 KB limit.");
                state.malformedRequest = true;
                state.requestComplete = true;
                
                HttpResponse response = new HttpResponse(431, "Request Header Fields Too Large");
                response.setKeepAlive(false);
                response.setBody("431: Request Header Fields Too Large");
                
                state.writeBuffer = response.toByteBuffer();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            
            state.boundaryIndex = findHeaderBoundary(currentBytes);
            if (state.boundaryIndex != -1) {
                state.headersParsed = true;
                String headers = new String(currentBytes, 0, state.boundaryIndex, java.nio.charset.StandardCharsets.UTF_8);
                state.contentLength = extractContentLength(headers);

                if (state.contentLength == -1) {
                    state.malformedRequest = true;
                    state.requestComplete = true;
                    HttpResponse response = new HttpResponse(400, "Bad Request");
                    response.setKeepAlive(false);
                    state.writeBuffer = response.toByteBuffer();
                    key.interestOps(SelectionKey.OP_WRITE);
                    return;
                }
            }
        }

        // Step B: Wait for the full body payload (if any) based on Content-Length
        if (state.headersParsed && !state.requestComplete && !state.malformedRequest) {
            
            if (state.contentLength > MAX_BODY_SIZE) {
                System.err.println("Security Alert: Declared body size exceeds 10 MB limit.");
                state.malformedRequest = true;
                state.requestComplete = true;
                
                HttpResponse response = new HttpResponse(413, "Payload Too Large");
                response.setKeepAlive(false);
                response.setBody("413: Payload Too Large");
                
                state.writeBuffer = response.toByteBuffer();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            int expectedTotalBytes = state.boundaryIndex + 4 + state.contentLength; 
            
            if (currentBytes.length > (state.boundaryIndex + 4 + MAX_BODY_SIZE)) {
                System.err.println("Security Alert: Actual stream payload exceeded 10 MB limit.");
                state.malformedRequest = true;
                state.requestComplete = true;
                
                HttpResponse response = new HttpResponse(413, "Payload Too Large");
                response.setKeepAlive(false);
                response.setBody("413: Payload Too Large");
                
                state.writeBuffer = response.toByteBuffer();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            // Execute logic only when the entire request (headers + body) is present
            if (currentBytes.length >= expectedTotalBytes) {
                state.requestComplete = true;
                
                // --- PHASE 5.7: TRACK REQUESTS PER CONNECTION ---
                state.requestsProcessed++;
                
                // Slice EXACTLY the bytes for this request
                byte[] exactRequestBytes = java.util.Arrays.copyOfRange(currentBytes, 0, expectedTotalBytes);
                // Preserve trailing bytes for TCP pipelining (the next request)
                byte[] leftoverBytes = java.util.Arrays.copyOfRange(currentBytes, expectedTotalBytes, currentBytes.length);
                
                state.accumulator.reset(); 
                try {
                    state.accumulator.write(leftoverBytes);
                } catch (IOException ignored) {}

                try {
                    HttpParser parser = new HttpParser();
                    state.request = parser.parseRequest(exactRequestBytes, state.boundaryIndex);
                    
                    HttpResponse response = router.route(state.request);
                    
                    // --- PHASE 5.7: ENFORCE MAX REQUESTS PER CONNECTION ---
                    // Override keep-alive to false if the socket has lived too long
                    boolean allowKeepAlive = state.request.isKeepAlive() && (state.requestsProcessed < MAX_REQUESTS_PER_CONNECTION);
                    response.setKeepAlive(allowKeepAlive);
                    
                    state.writeBuffer = response.toByteBuffer();
                    key.interestOps(SelectionKey.OP_WRITE);
                    
                } catch (Exception e) {
                    state.malformedRequest = true;
                    HttpResponse response = new HttpResponse(500, "Internal Server Error");
                    response.setKeepAlive(false);
                    response.setBody("500: Internal Server Error");
                    state.writeBuffer = response.toByteBuffer();
                    key.interestOps(SelectionKey.OP_WRITE);
                }
            }
        }
    }
}

/*
 * If you allocate an 8192-byte buffer, and the OS delivers 100 bytes:
 * 
 * After channel.read(): position = 100, limit = 8192.
 * 
 * If you try to extract bytes now, it starts reading at index 100 (which is
 * empty).
 * 
 * After flip(): position = 0, limit = 100.
 * 
 * Now, get() extracts exactly the 100 valid bytes and stops.
 * 
 * After clear(): position = 0, limit = 8192.
 * 
 * The buffer is wiped and ready for the next fragment.
 */