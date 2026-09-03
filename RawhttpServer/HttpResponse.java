import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponse {

    private final int statusCode;
    private final String statusMessage;

    /*
     * LinkedHashMap makes response headers deterministic.
     *
     * HashMap does not guarantee iteration order.
     *
     * This is not required by HTTP, but makes debugging and
     * testing much easier.
     */
    private final Map<String, String> headers =
            new LinkedHashMap<>();

    private byte[] bodyBytes;

    /*
     * IMPORTANT:
     *
     * This value represents the actual HTTP entity length.
     *
     * For HEAD requests:
     *
     *      bodyBytes = null
     *      contentLength = original GET body length
     *
     * Therefore Content-Length remains correct even though
     * no body bytes are transmitted.
     */
    private int contentLength = 0;


    public HttpResponse(int statusCode, String statusMessage) {

        this.statusCode = statusCode;
        this.statusMessage = statusMessage;

        // The reactor makes the final keep-alive decision after it has parsed the request.
        // Closing by default is the safe behavior if a response is serialized elsewhere.
        headers.put("Connection", "close");
        headers.put("Content-Length", "0");
    }


    /*
     * ============================================================
     * CENTRALIZED ERROR RESPONSE
     * ============================================================
     */
    public static HttpResponse createErrorResponse(
            int statusCode,
            String statusMessage) {

        HttpResponse response =
                new HttpResponse(statusCode, statusMessage);

        /*
         * Error responses close the connection by default.
         *
         * The server should not continue using a connection after
         * serious malformed-request errors.
         */
        response.setKeepAlive(false);

        response.setBody(
                statusCode + ": " + statusMessage
        );

        return response;
    }


    /*
     * ============================================================
     * HEADER
     * ============================================================
     */
    public void addHeader(String key, String value) {

        /*
         * Remove accidental duplicate case variants.
         *
         * HTTP header names are case-insensitive.
         */
        String existingKey = null;

        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(key)) {
                existingKey = existing;
                break;
            }
        }

        if (existingKey != null) {
            headers.put(existingKey, value);
        } else {
            headers.put(key, value);
        }
    }


    /*
     * ============================================================
     * CONNECTION
     * ============================================================
     */
    public void setKeepAlive(boolean keepAlive) {

        addHeader(
                "Connection",
                keepAlive ? "keep-alive" : "close"
        );
    }

    /** Whether this response may safely keep the underlying connection open. */
    public boolean isKeepAlive() {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("Connection")) {
                return "keep-alive".equalsIgnoreCase(entry.getValue());
            }
        }
        return false;
    }


    /*
     * ============================================================
     * TEXT BODY
     * ============================================================
     */
    public void setBody(String bodyText) {

        byte[] bytes =
                bodyText.getBytes(StandardCharsets.UTF_8);

        setBody(bytes);

        addHeader(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );
    }


    /*
     * ============================================================
     * BINARY BODY
     * ============================================================
     */
    public void setBody(byte[] bodyBytes) {

        this.bodyBytes = bodyBytes;

        this.contentLength =
                bodyBytes == null
                        ? 0
                        : bodyBytes.length;

        addHeader(
                "Content-Length",
                String.valueOf(contentLength)
        );
    }


    /*
     * ============================================================
     * HEAD SUPPORT
     * ============================================================
     *
     * We remove the actual body bytes.
     *
     * BUT:
     *
     * contentLength is NOT changed.
     *
     * Example:
     *
     * GET /hello
     *
     * Content-Length: 12
     *
     * HEAD /hello
     *
     * Content-Length: 12
     * [NO BODY]
     */
    public void clearBodyForHead() {

        bodyBytes = null;

        /*
         * DO NOT change contentLength.
         */
    }


    public byte[] getBodyBytes() {
        return bodyBytes;
    }


    /*
     * ============================================================
     * SERIALIZATION
     * ============================================================
     */
    public ByteBuffer toByteBuffer() {

        StringBuilder headerBuilder =
                new StringBuilder();

        /*
         * Status line
         */
        headerBuilder
                .append("HTTP/1.1 ")
                .append(statusCode)
                .append(" ")
                .append(statusMessage)
                .append("\r\n");


        /*
         * Always use the preserved HTTP entity length.
         *
         * This is critical for HEAD.
         */
        addHeader(
                "Content-Length",
                String.valueOf(contentLength)
        );


        /*
         * Headers
         */
        for (Map.Entry<String, String> entry :
                headers.entrySet()) {

            headerBuilder
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append("\r\n");
        }


        /*
         * End of headers
         */
        headerBuilder.append("\r\n");


        byte[] headerBytes =
                headerBuilder
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);


        /*
         * If this is HEAD:
         *
         * bodyBytes == null
         *
         * Therefore zero body bytes are transmitted.
         */
        int bodyLengthToWrite =
                bodyBytes == null
                        ? 0
                        : bodyBytes.length;


        ByteBuffer buffer =
                ByteBuffer.allocate(
                        headerBytes.length +
                        bodyLengthToWrite
                );


        buffer.put(headerBytes);

        if (bodyBytes != null &&
                bodyLengthToWrite > 0) {

            buffer.put(bodyBytes);
        }


        /*
         * Switch from write mode → read mode.
         */
        buffer.flip();

        return buffer;
    }
}
