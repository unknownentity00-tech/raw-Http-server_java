import java.util.Map;
public class HttpRequest {
      private byte[] body; 
      private final String path ;
      private final String  protocol ;
      private final String method ;
      private final  Map<String, String> headers;

       HttpRequest(String method, String path , String  protocol , Map<String, String> headers, byte[] body) {
       this.method = method;
       this.path = path;
       this.protocol = protocol;
       this.headers = headers;
        this.body = body;
    } 
    
    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getProtocol() {
        return protocol;
    }
    
    public Map<String,  String > getHeaders() {
        return headers;
    }

     public byte[] getBody(){
         return body;
     }

}
