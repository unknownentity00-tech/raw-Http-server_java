
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;


public class HttpParser {
   
    // Strict byte-level boundary detector (now static)
    private static int findHeaderBoundary(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10) {
                return i;
            }
        }
        return -1;
    }
    
     public HttpRequest readRequest(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
         
         
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        int bytesRead;
      
             //sate trackers 
         boolean headersParsed = false;
         Integer contentLength = null;
         int boundaryIndex = -1;

        // Raw TCP accumulation loop
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            accumulator.write(buffer, 0, bytesRead);

            if (!headersParsed) {
                byte[] currentBytes = accumulator.toByteArray();
                boundaryIndex = findHeaderBoundary(currentBytes);
           if (boundaryIndex != -1) {
                    headersParsed = true;
                    
                    // Safely extract ONLY headers to find Content-Length
           String headersString = new String(currentBytes, 0, boundaryIndex, StandardCharsets.UTF_8);
                    String[] headerLines = headersString.split("\r\n");
                    String targetHeader = "content-length:";
                    
                    for (String line : headerLines) {
                        if (line.toLowerCase().startsWith(targetHeader)) {
                            String valueStr = line.substring(targetHeader.length()).trim();
                            contentLength = Integer.parseInt(valueStr);
                            break;
                        }
                    }
                }
            }

      // Phase 2: Wait for exact body byte count
            if (headersParsed) {
                int bodyBytesReceived = accumulator.size() - (boundaryIndex + 4);
                int expectedBodySize = (contentLength != null) ? contentLength : 0;

                if (bodyBytesReceived >= expectedBodySize) {
                    break; // Request is mathematically complete
                }
            }
        }
//checks for Integrity 
            // Check 1: Was the header boundary ever found?
        if (boundaryIndex == -1) {
            throw new IOException("Malformed request or connection dropped before headers completed.");
        }
        
        // Check 2: Was the complete body received?
        int expectedBodySize = (contentLength != null) ? contentLength : 0;
        int actualBodySize = accumulator.size() - (boundaryIndex + 4);
        
        if (actualBodySize < expectedBodySize) {
            throw new IOException("Incomplete request: Expected " + expectedBodySize + 
                                  " body bytes, but received " + actualBodySize + ".");
        } 
        // --- 3. EXACT BYTE EXTRACTION ---
        byte[] fullRequestBytes = accumulator.toByteArray();

        // Extract headers to String using precise boundary index
        String headersString = new String(fullRequestBytes, 0, boundaryIndex, StandardCharsets.UTF_8);

        // Extract body strictly as a byte[] to preserve binary integrity
        byte[] bodyBytes = new byte[expectedBodySize];
        System.arraycopy(fullRequestBytes, boundaryIndex + 4, bodyBytes, 0, expectedBodySize);

       // --- 3. PARSE HEADERS AND CONSTRUCT OBJECT ---
        String[] headerLines = headersString.split("\r\n");
        
        // Parse Request Line (Index 0)
        String[] requestLine = headerLines[0].split(" ");
        if (requestLine.length != 3) {
            throw new IOException("Malformed HTTP request line.");
        }
        String method = requestLine[0];
        String path = requestLine[1];
        String protocol = requestLine[2];

        // Parse Headers Map (Index 1 to end)
        Map<String, String> headersMap = new HashMap<>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            if (line.isEmpty()) continue;
            
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                // Normalize keys to lowercase for reliable retrieval
                headersMap.put(parts[0].trim().toLowerCase(), parts[1].trim());
            }
        }

        return new HttpRequest(method, path, protocol, headersMap, bodyBytes);
     }



}
