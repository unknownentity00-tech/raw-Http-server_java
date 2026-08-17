
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
    public static void main(String[] args) {
        int port = 8080;

        try {
            // 1. The Selector (The Traffic Cop)
            // This replaces your ExecutorService. It monitors multiple channels simultaneously.
            Selector selector = Selector.open();

            // 2. The Non-Blocking Server Socket
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            
            // CRITICAL: This mathematically prevents the thread from freezing during I/O
            serverChannel.configureBlocking(false); 

            // 3. Registration
            // Tell the Selector: "Wake me up ONLY when a new client attempts to connect."
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("NIO Event Loop started on port " + port);

            // 4. The Infinite Event Loop (The Reactor)
            while (true) {
                // select() blocks the thread until at least one OS-level event occurs
                selector.select();

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
                    if (key.isValid() && key.isAcceptable()) {
                        acceptConnection(key, selector);
                    }
                    
                    // --- STEP 3: Handle Incoming Bytes ---
                    if (key.isValid() && key.isReadable()) {
                        handleRead(key);
                    }
                   
                    // --- STEP 4: Handle Outgoing Bytes ---
                    if (key.isValid() && key.isWritable()) {
                        handleWrite(key);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal NIO error: " + e.getMessage());
        }
    }

    private static void acceptConnection(SelectionKey key, Selector selector) throws IOException {
        // We know the channel is a ServerSocketChannel because only the server 
        // was registered with OP_ACCEPT
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        
        // This accept() will NEVER block because the OS already told us a client is waiting
        SocketChannel clientChannel = serverChannel.accept();
        
        // You must also configure the individual client stream to be non-blocking
        clientChannel.configureBlocking(false);
        
        // Register the new client with the selector, but this time listen for bytes to READ
        // CRITICAL: We attach a dedicated ClientState object to this specific client's key.
        // This is how the single thread remembers who is who when OP_READ fires.
        clientChannel.register(selector, SelectionKey.OP_READ,new ClientState());
        
        System.out.println("Accepted non-blocking connection from " + clientChannel.getRemoteAddress());
    }

    // 3. Read and Accumulate
    private static void handleRead(SelectionKey key) {
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

            // 2. THE FLIP
            state.readBuffer.flip();

            // 3. Extract bytes
            byte[] chunk = new byte[state.readBuffer.remaining()];
            state.readBuffer.get(chunk);

            // 4. Accumulate
            state.accumulator.write(chunk);

            // 5. THE CLEAR
            state.readBuffer.clear();

            // --- HTTP COMPLETENESS CHECK ---
           processAccumulator(key, state, clientChannel);
            

          } catch (IOException e) {
            System.err.println("Connection reset by peer.");
            try {
                clientChannel.close();
                key.cancel();
            } catch (IOException ignore) {}
        }
    }
    // Mathematically scans for the 4-byte HTTP header boundary sequence
    private static int findHeaderBoundary(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i+1] == '\n' && data[i+2] == '\r' && data[i+3] == '\n') {
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
            if (end == -1) end = lowerHeaders.length();
            try {
                return Integer.parseInt(lowerHeaders.substring(start, end).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return 0;
    }
    
    private static void handleWrite(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        try {
            // 1. Actually transmit the bytes to the OS network buffer
            clientChannel.write(state.writeBuffer);
            
            if (state.request != null && state.request.isKeepAlive()) {
                System.out.println("Response sent. Keeping connection alive.");
                state.reset(); // Wipes HTTP state
                
                // --- PIPELINING TRAP FIX ---
                // If bytes are left over, process them instantly. Do not wait for OP_READ.
                if (state.accumulator.size() > 0) {
                    processAccumulator(key, state, clientChannel);
                    if (!state.requestComplete) {
                         key.interestOps(SelectionKey.OP_READ);
                    }
                } else {
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
            } catch (IOException ignore) {}
        }
    }

    private static void processAccumulator(SelectionKey key, ClientState state, SocketChannel clientChannel) throws IOException {
        byte[] currentBytes = state.accumulator.toByteArray();

        // Step A: Hunt for the \r\n\r\n boundary
        if (!state.headersParsed) {
            state.boundaryIndex = findHeaderBoundary(currentBytes);
            
            if (state.boundaryIndex != -1) {
                state.headersParsed = true;
                String headers = new String(currentBytes, 0, state.boundaryIndex, java.nio.charset.StandardCharsets.UTF_8);
                state.contentLength = extractContentLength(headers);
                
                if (state.contentLength == -1) {
                    state.malformedRequest = true;
                    state.requestComplete = true; 
                    HttpResponse response = new HttpResponse(400, "Bad Request");
                    state.writeBuffer = response.toByteBuffer();
                    key.interestOps(SelectionKey.OP_WRITE);
                    return;
                }
            }
        }

        // Step B: Body accumulation and parsing
        if (state.headersParsed && !state.requestComplete && !state.malformedRequest) {
            int expectedTotalBytes = state.boundaryIndex + 4 + state.contentLength; 
            
            if (currentBytes.length >= expectedTotalBytes) {
                state.requestComplete = true;
                
                // --- PHASE 4.3 PIPELINING: SLICE THE BYTES ---
                byte[] exactRequestBytes = java.util.Arrays.copyOfRange(currentBytes, 0, expectedTotalBytes);
                byte[] leftoverBytes = java.util.Arrays.copyOfRange(currentBytes, expectedTotalBytes, currentBytes.length);
                
                state.accumulator.reset(); 
                try {
                    state.accumulator.write(leftoverBytes);
                } catch (IOException ignored) {}

                try {
                    HttpParser parser = new HttpParser();
                    state.request = parser.parseRequest(exactRequestBytes, state.boundaryIndex);
                    
                    HttpResponse response = new HttpResponse(200, "OK");
                    response.setBody("Hello NIO! You asked for: " + state.request.getPath());
                    state.writeBuffer = response.toByteBuffer();
                    
                    key.interestOps(SelectionKey.OP_WRITE);
                    
                } catch (Exception e) {
                    state.malformedRequest = true;
                    HttpResponse response = new HttpResponse(500, "Internal Server Error");
                    state.writeBuffer = response.toByteBuffer();
                    key.interestOps(SelectionKey.OP_WRITE);
                }
            }
        }
    }
}
/*
If you allocate an 8192-byte buffer, and the OS delivers 100 bytes:

After channel.read(): position = 100, limit = 8192.

If you try to extract bytes now, it starts reading at index 100 (which is empty).

After flip(): position = 0, limit = 100.

Now, get() extracts exactly the 100 valid bytes and stops.

After clear(): position = 0, limit = 8192.

The buffer is wiped and ready for the next fragment.
*/


