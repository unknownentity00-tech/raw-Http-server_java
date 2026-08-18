import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private final int statusCode;
    private final String statusMessage;
    private final Map<String, String> headers = new HashMap<>();
    private byte[] bodyBytes;

    public HttpResponse(int statusCode, String statusMessage) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        
        // Add default headers
       this.headers.put("Connection", "keep-alive");
        this.headers.put("Content-Length", "0"); // Default until a body is set
    }
// --- PHASE 5.4: CENTRALIZED ERROR RESPONSE FACTORIES ---
    public static HttpResponse createErrorResponse(int statusCode, String statusMessage) {
        HttpResponse response = new HttpResponse(statusCode, statusMessage);
        response.setKeepAlive(false); // Errors default to closing connection unless specified otherwise
        response.setBody(statusCode + ": " + statusMessage);
        return response;
    }
    public void addHeader(String key, String value) {
        this.headers.put(key, value);
    }
      public void setKeepAlive(boolean keepAlive) {
        this.headers.put("Connection", keepAlive ? "keep-alive" : "close");
    }
    // Overload 1: For text/html bodies
    public void setBody(String bodyText) {
        byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
        setBody(bytes);
        addHeader("Content-Type", "text/plain; charset=UTF-8");
    }
    // Clears the body bytes for HEAD requests without resetting the Content-Length header
    public void clearBodyForHead() {
        this.bodyBytes = new byte[0];
    }
    public byte[] getBodyBytes() {
        return this.bodyBytes;
    }
    // Overload 2: For binary/raw byte bodies (preserves exact payload)
    public void setBody(byte[] bodyBytes) {
        this.bodyBytes = bodyBytes;
        // Mathematically guarantee Content-Length matches the byte payload
        addHeader("Content-Length", String.valueOf(bodyBytes.length));
    }

    // The NIO Integration Method
    public ByteBuffer toByteBuffer() {
        StringBuilder headerBuilder = new StringBuilder();
        
        // 1. Status Line
        headerBuilder.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
   
        int bodyLength = (bodyBytes != null) ? bodyBytes.length : 0;
        headers.put("Content-Length", String.valueOf(bodyLength));
             // 2. Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        
        // 3. Blank line separating headers from body
        headerBuilder.append("\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);

        // 4. Allocate exact capacity
        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + bodyLength);
        buffer.put(headerBytes);
        
        if (bodyBytes != null && bodyLength > 0) {
            buffer.put(bodyBytes);
        }
        // 5. Flip buffer for channel reading (Write Mode to Read Mode)
        buffer.flip();
        return buffer;
    }
}
