import java.util.Map;

public class HttpRequest {
    private final byte[] body; 
    private final String path;
    private final String protocol;
    private final String method;
    private final Map<String, String> headers;
    private final boolean keepAlive;

    public HttpRequest(String method, String path, String protocol, Map<String, String> headers, byte[] body, boolean keepAlive) {
        this.method = method;
        this.path = path;
        this.protocol = protocol;
        this.headers = headers;
        this.body = body;
        this.keepAlive = keepAlive;
    } 
    
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getProtocol() { return protocol; }
    public Map<String, String> getHeaders() { return headers; }
    
    public String getHeader(String name) {
        if (headers == null || name == null) return null;
        return headers.get(name.toLowerCase());
    }
    
    public byte[] getBody() { return body; }
    public boolean isKeepAlive() { return keepAlive; }
}