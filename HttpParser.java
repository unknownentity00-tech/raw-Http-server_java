
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;


public class HttpParser {
    
    // Strict byte-level boundary detector
    private int findHeaderBoundary(byte[] data) {
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

        return null; // Next step: apply integrity checks and build HttpRequest
     }



}
