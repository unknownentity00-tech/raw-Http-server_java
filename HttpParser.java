
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;


import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;


public class HttpParser {
    
   private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
    private static final String SUPPORTED_VERSION = "HTTP/1.1";

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
            
                if(boundaryIndex != -1){
                    headersParsed = true;
                    
                    // Safely extract ONLY headers to find Content-Length
           String headersString = new String(currentBytes, 0, boundaryIndex,
           StandardCharsets.UTF_8);

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
       boolean keepAlive = true;
        if (protocol.equals("HTTP/1.0")) {
            keepAlive = false; 
        }
        String connectionHeader = headersMap.get("connection");
        if (connectionHeader != null) {
            if (connectionHeader.equalsIgnoreCase("close")) {
                keepAlive = false;
            } else if (connectionHeader.equalsIgnoreCase("keep-alive")) {
                keepAlive = true;
            }
        }

        return new HttpRequest(method, path, protocol, headersMap, bodyBytes, keepAlive);
  
     }

   // --- PHASE 5.1 HARDENED NIO PARSE REQUEST ---
    public HttpRequest parseRequest(byte[] requestBytes, int boundaryIndex) {
        
        // 1. Extract just the headers part as a String
        String headerBlock = new String(requestBytes, 0, boundaryIndex, StandardCharsets.UTF_8);

        // 2. Split the headerBlock into individual lines
        String[] lines = headerBlock.split("\r\n");
        
        if (lines.length == 0) {
            throw new IllegalArgumentException("Malformed Request: Empty request line");
        }

        // 3. Validate Request Line Components (Must be exactly 3: Method Path Version)
        String requestLine = lines[0];
        String[] requestLineParts = requestLine.split(" ");
        if (requestLineParts.length != 3) {
            throw new IllegalArgumentException("Malformed Request Line: Must have exactly 3 components. Found: " + requestLineParts.length);
        }

        String method = requestLineParts[0];
        String path = requestLineParts[1];
        String protocol = requestLineParts[2];

        // 4. Validate Method against supported set
        if (!SUPPORTED_METHODS.contains(method.toUpperCase())) {
            throw new IllegalArgumentException("Unsupported or Invalid HTTP Method: " + method);
        }

        // 5. Validate Path (Must start with '/' and not be empty)
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid Request Path: " + path);
        }

        // 6. Validate HTTP Version
        if (!protocol.equalsIgnoreCase(SUPPORTED_VERSION)) {
            throw new IllegalArgumentException("Unsupported HTTP Version: " + protocol + ". Expected HTTP/1.1");
        }

        // 7. Parse and Validate Headers strictly
        Map<String, String> headersMap = new HashMap<>();
        boolean contentLengthFound = false; // Guard against duplicate Content-Length

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;

            // Every header line must contain a colon ':'
            int colonIndex = line.indexOf(':');
            if (colonIndex == -1) {
                throw new IllegalArgumentException("Malformed Header Line (Missing colon): " + line);
            }

           String headerName = line.substring(0, colonIndex).trim();
            String headerValue = line.substring(colonIndex + 1).trim();

            if (!isValidHeaderName(headerName)) {
                throw new IllegalArgumentException("Invalid header name");
            }
            // Header name must be non-empty
            if (headerName.isEmpty()) {
                throw new IllegalArgumentException("Malformed Header Line: Header name cannot be empty.");
            }

            // Check for duplicate Content-Length and validate its numeric value
            if (headerName.equalsIgnoreCase("Content-Length")) {
                if (contentLengthFound) {
                    throw new IllegalArgumentException("Malformed Request: Duplicate Content-Length header detected.");
                }
                contentLengthFound = true;

                try {
                    long contentLength = Long.parseLong(headerValue);
                    if (contentLength < 0) {
                        throw new IllegalArgumentException("Invalid Content-Length: Value cannot be negative.");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid Content-Length: Not a valid numeric value.");
                }
            }

            headersMap.put(headerName.toLowerCase(), headerValue);
        }

        // 8. Extract the binary body (everything after \r\n\r\n)
        byte[] bodyBytes = Arrays.copyOfRange(requestBytes, boundaryIndex + 4, requestBytes.length);

        // Keep-Alive Logic mapped directly from headers
        boolean keepAlive = true; 
        if (protocol.equals("HTTP/1.0")) {
            keepAlive = false;
        }
        String connectionHeader = headersMap.get("connection");
        if (connectionHeader != null) {
            if (connectionHeader.equalsIgnoreCase("close")) {
                keepAlive = false;
            } else if (connectionHeader.equalsIgnoreCase("keep-alive")) {
                keepAlive = true;
            }
        }
        
        // 9. Construct and return your HttpRequest object
        return new HttpRequest(method, path, protocol, headersMap, bodyBytes, keepAlive);
    }
    // Validates header names to reject empty strings, spaces, tabs, and control characters
    private static boolean isValidHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Reject spaces, tabs, control characters, or invalid token symbols
            if (c <= 32 || c >= 127 || c == '(' || c == ')' || c == '<' || c == '>' || 
                c == '@' || c == ',' || c == ';' || c == ':' || c == '\\' || c == '"' || 
                c == '/' || c == '[' || c == ']' || c == '?' || c == '=' || c == '{' || c == '}') {
                return false;
            }
        }
        return true;
    }

}