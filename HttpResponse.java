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
        this.headers.put("Connection", "close");
        this.headers.put("Content-Length", "0"); // Default until a body is set
    }

    public void addHeader(String key, String value) {
        this.headers.put(key, value);
    }

    // Overload 1: For text/html bodies
    public void setBody(String bodyText) {
        byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
        setBody(bytes);
        addHeader("Content-Type", "text/plain; charset=UTF-8");
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
        
        // 2. Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        
        // 3. Blank line separating headers from body
        headerBuilder.append("\r\n");

        // Convert the entire header block to bytes
        byte[] headerBytes = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);
        int bodyLength = (bodyBytes != null) ? bodyBytes.length : 0;

        // 4. Allocate exact capacity
        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + bodyLength);
        buffer.put(headerBytes);
        
        if (bodyBytes != null) {
            buffer.put(bodyBytes);
        }

        // 5. Flip the buffer so it is in "Read Mode" for the SocketChannel
        buffer.flip();
        return buffer;
    }
}
