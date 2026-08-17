
    import java.io.IOException;
import java.net.InetSocketAddress;
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
            int bytesRead = clientChannel.read(state.buffer);

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

            System.out.println("Accumulated chunk of " + bytesRead + " bytes. Total client memory: " + state.accumulator.size() + " bytes.");

        } catch (IOException e) {
            System.err.println("Connection reset by peer.");
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


