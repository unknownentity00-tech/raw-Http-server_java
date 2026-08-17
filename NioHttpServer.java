
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
                    if (key.isAcceptable()) {
                        acceptConnection(key, selector);
                    }
                    
                    // --- STEP 3 & 4: Handle Incoming Bytes ---
                    else if (key.isReadable()) {
                        handleRead(key);
                        // readRequest(key);
                    }
                   
                    else if (key.isWritable()) {
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

           // 2. THE FLIP: Lock the 'limit' at the current 'position', and reset 'position' to 0.
            // This switches the buffer from "Write Mode" to "Read Mode".
            state.readBuffer.flip();

            // 3. Extract the exact number of bytes we just received.
            byte[] chunk = new byte[state.readBuffer.remaining()];

            state.readBuffer.get(chunk);

            // 4. Append the fragment to the persistent memory block.
            state.accumulator.write(chunk);

            // 5. THE CLEAR: Reset 'position' to 0 and 'limit' to capacity.
            // This switches the buffer back to "Write Mode" for the next OP_READ event.
            state.readBuffer.clear();

           // --- HTTP COMPLETENESS CHECK ---
            byte[] currentBytes = state.accumulator.toByteArray();

            // Step A: Hunt for the \r\n\r\n boundary if we haven't found it yet
            if (!state.headersParsed) {
                state.boundaryIndex = findHeaderBoundary(currentBytes);
                
                if (state.boundaryIndex != -1) {
                    state.headersParsed = true;
                    // Extract headers as a string to find Content-Length
            String headers = new String(currentBytes, 0, state.boundaryIndex, java.nio.charset.StandardCharsets.UTF_8);
                    state.contentLength = extractContentLength(headers);
                    if (state.contentLength == -1) {
                        System.err.println("Protocol Violation: Malformed Content-Length.");
                        state.malformedRequest = true;
                        state.requestComplete = true; 
                        
                        // NEW: Load the 400 error into the buffer and switch to OP_WRITE
                        String responseString = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        state.writeBuffer = ByteBuffer.wrap(responseString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        key.interestOps(SelectionKey.OP_WRITE);
                    } else  {
                        System.out.println("Headers received. Boundary at index " + state.boundaryIndex + ", Content-Length: " + state.contentLength);
                    }
                }
            }

            // Step B: If headers are known, check if the exact byte count for the body has arrived
            if (state.headersParsed && !state.requestComplete && !state.malformedRequest) {
                int expectedTotalBytes = state.boundaryIndex + 4 + state.contentLength; // +4 accounts for \r\n\r\n
                
                if (currentBytes.length >= expectedTotalBytes) {
                    state.requestComplete = true;
                  
                    try {
                        // 1. Instatiate the parser and extract the object
                        HttpParser parser = new HttpParser();
                        state.request = parser.parseRequest(currentBytes, state.boundaryIndex);
                        // NEW: Build the 200 OK buffer ONCE, using strict byte length
                       String body = "Hello NIO! You asked for: " + state.request.getPath();
                       byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        
                        String headers = "HTTP/1.1 200 OK\r\n" +
                                         "Content-Type: text/plain; charset=UTF-8\r\n" +
                                         "Content-Length: " + bodyBytes.length + "\r\n" +
                                         "Connection: close\r\n\r\n";
                        byte[] headerBytes = headers.getBytes(java.nio.charset.StandardCharsets.UTF_8);state.writeBuffer = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
                        // Allocate exact capacity, write both parts, and flip for reading by the channel
                        state.writeBuffer.put(headerBytes);
                        state.writeBuffer.put(bodyBytes);
                        state.writeBuffer.flip();
                        // 2. Switch the OS interrupt from OP_READ to OP_WRITE
                        key.interestOps(SelectionKey.OP_WRITE);
                        
                    } catch (Exception e) {
                     System.err.println("Parser failed: " + e.getMessage());
                        state.malformedRequest = true;
                        
                        // NEW: Load the 500 Internal Server Error into the buffer
                        String responseString = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        state.writeBuffer = ByteBuffer.wrap(responseString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                    // The request is ready. Next step: transition to parsing/writing.
                }
            }

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
            // 1. Drain the buffer that handleRead already prepared
            clientChannel.write(state.writeBuffer);

            // 2. If the OS network buffer gets full, hasRemaining() is true.
            // We return immediately and wait for the next OP_WRITE event.
            if (state.writeBuffer.hasRemaining()) {
                return; 
            }

            // 3. Buffer is mathematically empty. Safe to close.
            System.out.println("Response fully sent. Closing connection.");
            clientChannel.close();
            key.cancel();

        } catch (IOException e) {
            System.err.println("Write failed: " + e.getMessage());
            try {
                clientChannel.close();
                key.cancel();
            } catch (IOException ignore) {}
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


