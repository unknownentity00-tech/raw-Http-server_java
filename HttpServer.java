import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.nio.charset.StandardCharsets;

public class HttpServer {

    public static void main(String[] args) {
        int port = 8080;
        ExecutorService threadPool = Executors.newFixedThreadPool(4);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);

            while (true) {
                // 1. Accept the connection
                Socket clientSocket = serverSocket.accept();
                
                // 2. Hand it to the worker thread immediately and loop back
                threadPool.submit(new ClientWorker(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Fatal server error: " + e.getMessage());
        }finally{
            //// This guarantees the thread pool is killed ,Stop accepting new tasks, but allow already-submitted tasks to finish.
           
            // 1. Stop accepting new tasks
            threadPool.shutdown(); 
            
            try {
                // 2. Wait up to 5 seconds for existing clients to finish
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    
                    System.err.println("Workers did not terminate in time. Forcing shutdown...");
                    // 3. Brutally interrupt any hanging threads
                    threadPool.shutdownNow(); 
                }
            } catch (InterruptedException e) {
                // 4. If the shutdown process itself is interrupted, force kill everything
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            System.out.println("Server fully terminated.");
        }
    }
}
