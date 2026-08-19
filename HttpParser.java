import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Strict parser for one already-complete HTTP/1.1 request. */
public final class HttpParser {
    private static final Set<String> METHODS = new HashSet<>(
            Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
    private static final int MAX_HEADERS = 100;
    private static final int MAX_SINGLE_HEADER = 8 * 1024;
    /** Shared request-body limit, enforced before a request is buffered in full. */
    public static final int MAX_BODY_SIZE = 10 * 1024 * 1024;

    public HttpRequest parseRequest(byte[] request, int boundary) {
        if (boundary <= 0 || boundary + 4 > request.length) throw new IllegalArgumentException("Malformed request");
        String[] lines = new String(request, 0, boundary, StandardCharsets.ISO_8859_1).split("\\r\\n", -1);
        if (lines.length == 0 || lines[0].isEmpty() || lines.length - 1 > MAX_HEADERS) {
            throw new IllegalArgumentException("Malformed request headers");
        }
        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || requestLine[0].isEmpty() || requestLine[1].isEmpty()) {
            throw new IllegalArgumentException("Malformed request line");
        }
        String method = requestLine[0].toUpperCase(Locale.ROOT);
        String path = requestLine[1];
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported HTTP method");
        if (!path.startsWith("/")) throw new IllegalArgumentException("Invalid request path");
        if (!"HTTP/1.1".equals(requestLine[2])) throw new IllegalArgumentException("Only HTTP/1.1 is supported");

        Map<String, String> headers = new HashMap<>();
        boolean host = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (line.length() > MAX_SINGLE_HEADER) throw new IllegalArgumentException("Request header field too large");
            if (colon <= 0) throw new IllegalArgumentException("Malformed header line");
            String name = line.substring(0, colon);
            if (!validHeaderName(name)) throw new IllegalArgumentException("Invalid header name");
            String key = name.toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (!validHeaderValue(value)) throw new IllegalArgumentException("Invalid header value");
            if ("transfer-encoding".equals(key)) throw new IllegalArgumentException("Transfer-Encoding is not supported");
            if ("content-length".equals(key)) {
                validateLength(value);
                if (headers.containsKey(key)) throw new IllegalArgumentException("Duplicate Content-Length");
            }
            if ("host".equals(key)) {
                if (value.isEmpty()) throw new IllegalArgumentException("Empty Host header");
                if (headers.containsKey(key)) throw new IllegalArgumentException("Duplicate Host header");
                host = true;
            }
            // Most repeated request fields use comma-separated list semantics. Preserve all
            // values in the map while rejecting the security-sensitive fields above.
            headers.merge(key, value, (previous, next) -> previous + ", " + next);
        }
        if (!host) throw new IllegalArgumentException("Missing Host header");
        return new HttpRequest(method, path, "HTTP/1.1", headers,
                Arrays.copyOfRange(request, boundary + 4, request.length),
                !"close".equalsIgnoreCase(headers.get("connection")));
    }

    private static void validateLength(String value) {
        try {
            long length = Long.parseLong(value);
            if (length < 0 || length > MAX_BODY_SIZE) throw new NumberFormatException();
        }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Content-Length"); }
    }

    private static boolean validHeaderName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) return false;
        }
        return true;
    }

    private static boolean validHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Horizontal tab is permitted; all other C0 controls and DEL are rejected.
            if ((c < 32 && c != '\t') || c == 127) return false;
        }
        return true;
    }
}
