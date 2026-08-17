import java.util.HashMap;
import java.util.Map;

public class Router {
    private final Map<String, RouteHandler> routes = new HashMap<>();

    public void addRoute(String method, String path, RouteHandler handler) {
        String key = method.toUpperCase() + " " + path;
        routes.put(key, handler);
    }

    public HttpResponse route(HttpRequest request) {
        String key = request.getMethod().toUpperCase() + " " + request.getPath();
        RouteHandler handler = routes.get(key);
        
        if (handler != null) {
            try {
                return handler.handle(request);
            } catch (Exception e) {
                System.err.println("Handler crashed: " + e.getMessage());
                return new HttpResponse(500, "Internal Server Error");
            }
        }
        
        HttpResponse response = new HttpResponse(404, "Not Found");
        response.setBody("404: The endpoint " + request.getPath() + " does not exist.");
        return response;
    }
}