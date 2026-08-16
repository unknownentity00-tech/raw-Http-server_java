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

            // State trackers for the read loop
            boolean headersParsed = false;
            int contentLength = 0;
            int boundaryIndex = -1;

            // 1. Let the loop handle ALL reading. 
              // 1. The state-aware read loop
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                accumulator.write(buffer, 0, bytesRead);
                
                // Phase 1: Wait for headers to finish and extract Content-Length
                if (!headersParsed) {
                    String currentData = accumulator.toString(StandardCharsets.UTF_8);
                    boundaryIndex = currentData.indexOf("\r\n\r\n");
                    
                    if (boundaryIndex != -1) {
                        headersParsed = true; 
                        
                        String headersString = currentData.substring(0, boundaryIndex);
                        String[] headerLines = headersString.split("\r\n");
                        
                        for (String line : headerLines) {
                            if (line.toLowerCase().startsWith("content-length:")) {
                                String valueStr = line.substring(15).trim();
                                contentLength = Integer.parseInt(valueStr);
                                break;
                            }
                        }
                    }
                }

                if (headersParsed) {
                    int bodyBytesReceived = accumulator.size() - (boundaryIndex + 4);
                    
                    if (bodyBytesReceived >= contentLength) {
                        break; // We have the complete headers and body!
                    }
                }
            }
            
           if (boundaryIndex == -1) {
                System.out.println("Malformed request or dropped connection. Closing socket.");
                clientSocket.close();
                continue; // This correctly skips the rest of the while(true) loop and waits for a new client
            }


            
            // 2. Print the fully accumulated request, not a discarded partial string
          //  String fullRequest = accumulator.toString(StandardCharsets.UTF_8);
          //  System.out.println("Request:\n" + fullRequest);

           // 2. Separate headers and body
              String fullRequest = accumulator.toString(StandardCharsets.UTF_8);
              String headers = fullRequest.substring(0, boundaryIndex);
              String body = fullRequest.substring(boundaryIndex + 4);

              System.out.println("HEADERS:\n" + headers);
              System.out.println("\nBODY:\n" + (body.isEmpty() ? "[Empty Body]" : body));
            
            String bodyText =  " hello welcome to my server";
            byte[] bodybytes = bodyText.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + bodybytes.length + "\r\n" +
                    "\r\n";

           byte[] headerBytes =header.getBytes(StandardCharsets.UTF_8);
            outputStream.write(headerBytes);            
            outputStream.write(bodybytes);

            outputStream.flush();// Flush the output stream to ensure all data is sent

            clientSocket.close();
            System.out.println("Response sent and client connection closed.");
        }
    }
}