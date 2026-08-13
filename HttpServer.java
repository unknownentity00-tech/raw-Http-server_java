import java.net.ServerSocket;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
 import java.nio.charset.StandardCharsets;


public class HttpServer {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8080);
        System.out.println("Server started...");
        
        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected...");
            InputStream inputStream = clientSocket.getInputStream();
            OutputStream outputStream = clientSocket.getOutputStream();
            
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
            int bytesRead;

            // 1. Let the loop handle ALL reading. 
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                
                accumulator.write(buffer, 0, bytesRead);
                String currentData = accumulator.toString(StandardCharsets.UTF_8);
                
                if (currentData.contains("\r\n\r\n")) {
                    break;
                }
            }
          
            // 2. Print the fully accumulated request, not a discarded partial string
            String fullRequest = accumulator.toString(StandardCharsets.UTF_8);
            System.out.println("Request:\n" + fullRequest);
            
           
            
            clientSocket.close();
        }
    }
}