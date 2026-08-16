import java.net.ServerSocket;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
 import java.nio.charset.StandardCharsets;


public class HttpServer {


// Strict byte-level boundary detector to prevent character encoding corruption

    private static int findHeaderBoundary(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10) {
                return i;
            }
        }
        return -1;
    }
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
            Integer contentLength = null;// null means reacder absent 
            int boundaryIndex = -1;
            
            // --- 1. STATE-AWARE READ LOOP ---
              // 1. The state-aware read loop
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                accumulator.write(buffer, 0, bytesRead);
                
                // Phase 1: Wait for headers to finish and extract Content-Length
                if (!headersParsed) {
                  byte[] currentBytes = accumulator.toByteArray();
                    boundaryIndex = findHeaderBoundary(currentBytes);
                    
                    if (boundaryIndex != -1) {
                        headersParsed = true;
                        
                        // Convert ONLY the headers to a String, safely ignoring the body
                        String headersString = new String(currentBytes, 0, boundaryIndex, StandardCharsets.UTF_8);
                        String[] headerLines = headersString.split("\r\n");
                        // Issue 1: Remove magic number 15
                        String targetHeader = "content-length:";
                        
                        for (String line : headerLines) {
                           
                                if (line.toLowerCase().startsWith(targetHeader)) {
                                // Dynamically calculate where the value starts based on the header string length
                                String valueStr = line.substring(targetHeader.length()).trim();
                                contentLength = Integer.parseInt(valueStr);
                                break;
                                }
                            
                        }
                    }
                }
             // Phase 2: Wait for the full body to arrive
                if (headersParsed) {
                    int bodyBytesReceived = accumulator.size() - (boundaryIndex + 4);

                     // Issue 2: Handle the null state. If null, we expect 0 body bytes.
                    int expectedBodySize = (contentLength != null) ? contentLength : 0;

                    if (bodyBytesReceived >= expectedBodySize) {
                        break; // We have the complete headers and body!
                    }
                }
            }

            // --- 2. SAFETY & INTEGRITY CHECKS ---
            // Check 1: Did the client drop before sending \r\n\r\n?
           if (boundaryIndex == -1) {
                System.out.println("Malformed request or dropped connection. Closing socket.");
                clientSocket.close();
                continue; // This correctly skips the rest of the while(true) loop and waits for a new client
            }
           // Check 2: Did the client drop before sending the full payload?
           int expectedBodySize = (contentLength != null) ? contentLength : 0;
            int actualBodySize = accumulator.size() - (boundaryIndex + 4);

            if (actualBodySize < expectedBodySize) {
                System.out.println("Incomplete request: Expected " + expectedBodySize + 
                                   " body bytes, but received " + actualBodySize + ". Dropping connection.");
                clientSocket.close();
                continue;
            }
            
         

          // --- 3. EXACT BYTE EXTRACTION ---
              byte[] fullRequestBytes = accumulator.toByteArray();
              String headers = new String(fullRequestBytes, 0, boundaryIndex, StandardCharsets.UTF_8);
             // Strictly cap extraction at expectedBodySize to prevent pipelining corruption
            // Using Math.min ensures we don't throw an out-of-bounds exception if actualBodySize 
            // is somehow smaller (which shouldn't happen due to the integrity check above, but is mathematically safer).
              int bodyLength = Math.min(actualBodySize, expectedBodySize);
             String body = new String(fullRequestBytes, boundaryIndex + 4, bodyLength, StandardCharsets.UTF_8);
              System.out.println("HEADERS:\n" + headers);
              System.out.println("\nBODY:\n" + (body.isEmpty() ? "[Empty Body]" : body));
            
           // --- 4. REQUEST LINE PARSING (ROUTING) ---
            String[] headerLines = headers.split("\r\n");
            String requestLine = headerLines[0]; // e.g., "GET / HTTP/1.1"
           // Simplistic split logic (to be replaced with strict RFC 7230 tokenization later)
            String[] requestParts = requestLine.split(" ");
            
            String method = requestParts.length > 0 ? requestParts[0] : "UNKNOWN";
            String path = requestParts.length > 1 ? requestParts[1] : "UNKNOWN";
            String protocol = requestParts.length > 2 ? requestParts[2] : "UNKNOWN";

            System.out.println("--- INCOMING REQUEST ---");
            System.out.println("Method:   " + method);
            System.out.println("Path:     " + path);
            System.out.println("Protocol: " + protocol);
            System.out.println("Body:     " + (body.isEmpty() ? "[Empty Body]" : body));
            System.out.println("------------------------");

           // --- 5. SEND RESPONSE ---
           String bodyText =  " hello welcome to my server";
           byte[] bodybytes = bodyText.getBytes(StandardCharsets.UTF_8);
            
            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: " + bodybytes.length + "\r\n" +
                                    "\r\n";

            outputStream.write(responseHeader.getBytes(StandardCharsets.UTF_8));            
            outputStream.write(bodybytes);
            outputStream.flush();

            clientSocket.close();
            System.out.println("Response sent and client connection closed.");
        }
    }
}