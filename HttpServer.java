import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class HttpServer {

    public static void main(String[] args) {
        int port = 8080;

        // 1. Create ServerSocket
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);

            while (true) {
                // 2. Accept client (try-with-resources ensures the socket closes automatically)
                try (Socket clientSocket = serverSocket.accept()) {
                    
                    // 3 & 4. Get InputStream and OutputStream
                    InputStream input = clientSocket.getInputStream();
                    OutputStream output = clientSocket.getOutputStream();

                    // 5 & 6. Create HttpParser and read the request
                    HttpParser parser = new HttpParser();
                    HttpRequest request = parser.readRequest(input);

                    // 7. Print all parsed components
                    if (request != null) {
                        System.out.println("=== NEW REQUEST ===");
                        System.out.println("Method:   " + request.getMethod());
                        System.out.println("Path:     " + request.getPath());
                        System.out.println("Protocol: " + request.getProtocol());
                        System.out.println("Headers:  " + request.getHeaders());
                        
                        // Convert byte[] body to String solely for console visibility
                        if (request.getBody() != null && request.getBody().length > 0) {
                            String bodyString = new String(request.getBody(), StandardCharsets.UTF_8);
                            System.out.println("Body:     " + bodyString);
                        } else {
                            System.out.println("Body:     [Empty]");
                        }
                        System.out.println("===================");

                        // 8. Send the hardcoded 200 OK response
                        String responseBody = "Server architecture upgraded successfully.\n";
                        String httpResponse = 
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + responseBody.length() + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            responseBody;

                        output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }

                } catch (IOException e) {
                    System.err.println("Request dropped or malformed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal server binding error: " + e.getMessage());
        }
    }
}