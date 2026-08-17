import java.util.HashMap;
import java.util.Map;

public class Router {
    // Nested Dictionary: Path -> (Method -> Handler)
    private final Map<String, Map<String, RouteHandler>> routes = new HashMap<>();

    public void addRoute(String method, String path, RouteHandler handler) {
      routes.computeIfAbsent(path, k -> new HashMap<>())
              .put(method.toUpperCase(), handler);
    }

    public HttpResponse route(HttpRequest request) {
        String path = request.getPath();
        String method = request.getMethod().toUpperCase();

        Map<String, RouteHandler> pathRoutes = routes.get(path);
       // Scenario 1: Path does not exist at all -> 404
        if (pathRoutes == null) {
            HttpResponse response = new HttpResponse(404, "Not Found");
            response.setBody("404: The endpoint " + path + " does not exist.");
            return response;
        }

        RouteHandler handler = pathRoutes.get(method);

        // Scenario 2: Path exists, but HTTP Method is not registered -> 405
        if (handler == null) {
            HttpResponse response = new HttpResponse(405, "Method Not Allowed");
            //  Dynamically calculate and inject the Allow header ---
            String allowedMethods = String.join(", ", pathRoutes.keySet());
            response.addHeader("Allow", allowedMethods);
            
            response.setBody("405: Method " + method + " is not allowed for " + path + ".\nAllowed: " + allowedMethods);
            return response;
        }

        // Scenario 3: Execution and 500 Catch
        try {
            return handler.handle(request);
        } catch (Exception e) {
            System.err.println("Handler crashed: " + e.getMessage());
            HttpResponse response = new HttpResponse(500, "Internal Server Error");
            response.setBody("500: Internal Server Error");
            return response;
        }
}
}