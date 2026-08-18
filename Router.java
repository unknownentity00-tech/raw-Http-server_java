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
        // --- PHASE 5.3: HANDLE OPTIONS METHOD ---
        if (method.equals("OPTIONS")) {
            HttpResponse response = new HttpResponse(200, "OK");
            String allowedMethods = getImplicitAllowedMethods(pathRoutes); // <-- UPDATED HERE
            response.addHeader("Allow", allowedMethods);
            response.setBody(""); 
            return response;
        }

        RouteHandler handler = pathRoutes.get(method);

        // --- PHASE 5.3: HANDLE HEAD METHOD (Fallback to GET handler if HEAD isn't explicitly registered) ---
        if (handler == null && method.equals("HEAD")) {
            handler = pathRoutes.get("GET");
        }

        // Scenario 2: Path exists, but HTTP Method is not registered -> 405
        if (handler == null) {
            HttpResponse response = new HttpResponse(405, "Method Not Allowed");
            //  Dynamically calculate and inject the Allow header ---
            String allowedMethods = getImplicitAllowedMethods(pathRoutes);
            response.addHeader("Allow", allowedMethods);
            
            response.setBody("405: Method " + method + " is not allowed for " + path + ".\nAllowed: " + allowedMethods);
            return response;
        }

        // Scenario 3: Execution and 500 Catch
        try {
            HttpResponse response = handler.handle(request);
            // --- PHASE 5.3: STRIP BODY FOR HEAD REQUESTS (Keep Content-Length intact) ---
            if (request.getMethod().equalsIgnoreCase("HEAD")) {
                // Ensure Content-Length header is set based on the original body length before clearing it
               
                 if (response.getBodyBytes() != null) {
                    // Content-Length was already calculated by setBody(), just clear the payload bytes
                    response.clearBodyForHead();
                }
                
               // Clear body bytes for HEAD response
            }
           return response;
        } catch (Exception e) {
            System.err.println("Handler crashed: " + e.getMessage());
            HttpResponse response = new HttpResponse(500, "Internal Server Error");
            response.setBody("500: Internal Server Error");
            return response;
        }
}
private String getImplicitAllowedMethods(Map<String, RouteHandler> pathRoutes) {
        java.util.Set<String> methods = new java.util.LinkedHashSet<>(pathRoutes.keySet());
        // If GET is supported, implicitly support HEAD and OPTIONS
        if (methods.contains("GET")) {
            methods.add("HEAD");
        }
        methods.add("OPTIONS");
        return String.join(", ", methods);
    }
}