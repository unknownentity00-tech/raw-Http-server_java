import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
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
        }
    }
}
