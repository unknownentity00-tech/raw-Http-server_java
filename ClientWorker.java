import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientWorker implements Runnable {
    
    private final Socket clientSocket;

    public ClientWorker(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        // 1. Get the exact JVM identity of the current thread
        String threadName = Thread.currentThread().getName();
        
        try ( clientSocket; 
             InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            System.out.println("Worker: " + threadName + " | Accepted connection.");

            HttpParser parser = new HttpParser();
            HttpRequest request = parser.readRequest(input);
            
            System.out.println("Worker: " + threadName + " | Parsed " + request.getMethod() + " " + request.getPath());

            // 2.The Concurrency Test: Block this specific thread for 10 seconds
            System.out.println("Worker: " + threadName + " | Simulating heavy load. Sleeping for 10s...");
            Thread.sleep(10000);
            System.out.println("Worker: " + threadName + " | Awake. Sending response.");

            // 3.Send dynamic response proving which thread did the work
            String responseText = "Processed by background worker: " + threadName + "\n";
            byte[] responseBodyBytes = responseText.getBytes(StandardCharsets.UTF_8);
            
            String responseHeaders = 
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + responseBodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            byte[] headerBytes = responseHeaders.getBytes(StandardCharsets.UTF_8);

            output.write(headerBytes);
            output.write(responseBodyBytes);
            output.flush();

        } catch (IOException e) {
            System.err.println("Worker " + threadName + " | I/O Error: " + e.getMessage());
        } catch (InterruptedException e) {
            // Required when using Thread.sleep()
            System.err.println("Worker " + threadName + " | Interrupted!");
            Thread.currentThread().interrupt(); // Restore interrupt status
        }
    }
}