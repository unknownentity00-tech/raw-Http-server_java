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

        // --- NEW: The JVM Shutdown Hook ---
        // This intercepts Ctrl+C and OS kill signals to enforce graceful shutdown

       Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Shutdown Hook] Intercepted kill signal. Initiating graceful shutdown...");
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("[Shutdown Hook] Workers did not terminate in time. Forcing shutdown...");
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            System.out.println("[Shutdown Hook] Server fully terminated.");
        }));
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
        }

         // Note: The finally block is removed because the Shutdown
         // Hook now guarantees execution.


        /*finally{
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
        }*/
    }
}
//executer lifestyle 

/*
. What does shutdown() do?
It alters the state of the ExecutorService. It immediately rejects any new submit() calls. However, it is non-blocking. It allows the current workers to finish their active tasks, and it allows any tasks currently waiting in the queue to begin and finish. The main thread continues executing the next line of code instantly.

2. What does awaitTermination() do?
It is a blocking operation. It halts the main thread at that exact line of code and waits for a specific duration (e.g., 5 seconds). It waits for shutdown() to completely finish processing all active and queued tasks.

3. What happens if workers do not terminate in time?
If the 5 seconds expire and Worker-1 is still stuck processing a slow client, awaitTermination() unblocks and returns false. At this point, the graceful shutdown has failed. You are forced to call shutdownNow(), which sends a hardware interrupt to the remaining threads, brutally killing their network streams to prevent the server from hanging indefinitely. 
*/