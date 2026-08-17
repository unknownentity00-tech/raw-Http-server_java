
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
        clientChannel.register(selector, SelectionKey.OP_READ);
        
        System.out.println("Accepted non-blocking connection from " + clientChannel.getRemoteAddress());
    }
}


