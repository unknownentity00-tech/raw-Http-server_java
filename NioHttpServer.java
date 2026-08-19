import java.io.IOException;
import java.net.InetSocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class NioHttpServer {
    
    private static final int MAX_HEADER_SIZE = 16 * 1024;         // 16 KB limits memory exhaustion per request
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;    // 10 MB limits payload bombs
    private static final long CONNECTION_TIMEOUT_MS = 15000;      // 15 seconds max idle time
    
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
            // Tell the Selector: "Wake me up ONLY when a new client attempts to connect."
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("NIO Event Loop started on port " + port);

            // 4. The Infinite Event Loop (The Reactor)
            while (true) {
                // select(1000) guarantees the thread wakes up every 1 second minimum
                // This is required so enforceTimeouts() runs even if no network traffic arrives.
                selector.select(1000);

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    
                    // You MUST remove the key from the iterator, otherwise the loop
                    // will infinitely process the same event and crash.
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    // --- STEP 2: Handle New Connections ---
                    if (key.isAcceptable()) {
                        acceptConnection(key, selector);
                    }

                    // --- STEP 3: Handle Incoming Bytes ---
                    if (key.isReadable()) {
                        handleRead(key, router);
                    }

                    // --- STEP 4: Handle Outgoing Bytes ---
                    if (key.isWritable()) {
                        handleWrite(key, router);
                    }
                }
                
                // --- PHASE 5.6: DEADLINE ENFORCEMENT ---
                // MUST BE OUTSIDE the key iterator loop. It runs exactly once per reactor cycle.
                enforceTimeouts(selector);
            }
        } catch (IOException e) {
            System.err.println("Fatal NIO error: " + e.getMessage());
        }
    }

    // --- PHASE 5.6: Sweeps for dead connections ---
    private static void enforceTimeouts(Selector selector) {
        long now = System.currentTimeMillis();
        
        // selector.keys() returns ALL registered connections, active or idle
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ClientState) {
                ClientState state = (ClientState) key.attachment();
                
                if (now - state.lastActivityTime > CONNECTION_TIMEOUT_MS) {
                    System.out.println("Connection timeout. Terminating idle socket.");
                    try {
                        key.channel().close();
                    } catch (IOException ignored) {}
                    key.cancel();
                }
            }
        }
    }

    private static void acceptConnection(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        // This accept() will NEVER block because the OS already told us a client is waiting
        SocketChannel clientChannel = serverChannel.accept();

        // Configure the individual client stream to be non-blocking
        clientChannel.configureBlocking(false);

        // Register the new client with the selector, attaching a dedicated ClientState object.
        // This maintains isolated state (buffers, parse status) across multiple OP_READ events.
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
                clientChannel.close();
                key.cancel();
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
            try {
                clientChannel.close();
                key.cancel();
            } catch (IOException ignore) {
            }
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
            // 1. Actually transmit the bytes to the OS network buffer
            // --- PHASE 5.6: Capture bytes written to update activity ---
            int bytesWritten = clientChannel.write(state.writeBuffer);
            
            if (bytesWritten > 0) {
                state.updateActivity();
            }
            
            // 2. Check for partial writes. Yield thread if network buffer is full.
            if (state.writeBuffer.hasRemaining()) {
                return; 
            }
            
            // --- PHASE 5.5: CONNECTION LIFECYCLE & KEEP-ALIVE ---
            // The response headers (created during Phase 5.4) are the ultimate authority.
            boolean keepAlive = state.request != null && state.request.isKeepAlive();
            
            // Force close if request was malformed or tripped security limits
            if (state.malformedRequest) {
                keepAlive = false;
            }

            if (keepAlive) {
                System.out.println("Response sent. Keeping connection alive for pipelined/future requests.");
                
                // Reset state parameters but leave leftover accumulator bytes intact.
                state.reset(); 

                // Check for pipelined requests already sitting in the accumulator
                if (state.accumulator.size() > 0) {
                    processAccumulator(key, state, clientChannel, router);
                    // If processing didn't immediately flip us back to OP_WRITE (incomplete pipeline data), 
                    // we switch to OP_READ to wait for the rest.
                    if (!state.requestComplete) {
                        key.interestOps(SelectionKey.OP_READ);
                    }
                } else {
                    // No leftover bytes, wait for the next request cycle
                    key.interestOps(SelectionKey.OP_READ);
                }
            } else {
                System.out.println("Response sent. Connection: close.");
                clientChannel.close();
                key.cancel();
            }
        } catch (IOException e) {
            System.err.println("Write failed: " + e.getMessage());
            try {
                clientChannel.close();
                key.cancel();
            } catch (IOException ignore) {
            }
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
                    // Server policy controls ultimate keep-alive behavior
                    response.setKeepAlive(state.request.isKeepAlive());
                    
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
}/*
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