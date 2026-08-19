import java.util.HashMap;
import java.util.Map;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * The Router maps incoming URLs to the specific logic (RouteHandler) that should execute.
 * This class is inherently thread-safe during execution because the 'routes' map is 
 * only read from, never written to, once the server starts.
 */
public class Router {
    // Nested Dictionary: Path -> (Method -> Handler)
    // Example: "/users" -> {"GET" -> GetUsersHandler, "POST" -> CreateUserHandler}
    private final Map<String, Map<String, RouteHandler>> routes = new HashMap<>();

    public void addRoute(String method, String path, RouteHandler handler) {
      routes.computeIfAbsent(path, k -> new HashMap<>())
            .put(method.toUpperCase(), handler);
    }

    public HttpResponse route(HttpRequest request) {
        String path = request.getPath();
        String method = request.getMethod().toUpperCase();

        Map<String, RouteHandler> pathRoutes = routes.get(path);
        
        // SCENARIO 1: 404 Not Found
        // The client requested a URL that doesn't exist anywhere in our application.
        if (pathRoutes == null) {
            return HttpResponse.createErrorResponse(404, "Not Found: The endpoint " + path + " does not exist.");
        }
        
        // SCENARIO 2: OPTIONS Method (CORS and API Discovery)
        // We intercept this before looking for a handler because the server can automatically
        // figure out what methods are allowed without needing developer-written handlers.
        if (method.equals("OPTIONS")) {
            HttpResponse response = new HttpResponse(200, "OK");
            String allowedMethods = getImplicitAllowedMethods(pathRoutes); 
            response.addHeader("Allow", allowedMethods);
            response.setBody(""); 
            return response;
        }

        RouteHandler handler = pathRoutes.get(method);

        // SCENARIO 3: HEAD Method Fallback
        // A HEAD request is identical to a GET request, just without the body bytes.
        // If the developer didn't explicitly write a HEAD handler, we borrow the GET handler.
        if (handler == null && method.equals("HEAD")) {
            handler = pathRoutes.get("GET");
        }

        // SCENARIO 4: 405 Method Not Allowed
        // The path exists (e.g., "/users"), but the client used the wrong method (e.g., DELETE instead of POST).
        // We must tell the client what methods they are actually allowed to use here via the 'Allow' header.
        if (handler == null) {
            HttpResponse response = HttpResponse.createErrorResponse(405, "Method " + method + " is not allowed for " + path + ".");
            response.addHeader("Allow", getImplicitAllowedMethods(pathRoutes));
            return response;
        }

        // SCENARIO 5: Execution and Crash Safety (500 Internal Server Error)
        try {
            // Execute the actual endpoint logic
            HttpResponse response = handler.handle(request);
            
            // POST-PROCESSING for HEAD: 
            // The GET handler we borrowed executed fully and generated a payload.
            // We must keep the Content-Length calculation intact, but delete the actual bytes before transmission.
            if (request.getMethod().equalsIgnoreCase("HEAD") && response.getBodyBytes() != null) {
                response.clearBodyForHead();
            }
            return response;
        } catch (Exception e) {
            // If the developer's route logic throws an exception (e.g., database crash, null pointer),
            // we catch it here. This prevents the Worker Thread from dying and returns a 500 to the client.
            System.err.println("Handler crashed: " + e.getMessage());
            return HttpResponse.createErrorResponse(500, "Internal Server Error");
        }
    }

    /**
     * Calculates the contents of the HTTP 'Allow' header.
     * Uses LinkedHashSet to maintain insertion order, ensuring deterministic outputs for testing.
     */
    private String getImplicitAllowedMethods(Map<String, RouteHandler> pathRoutes) {
        Set<String> methods = new LinkedHashSet<>(pathRoutes.keySet());
        // If GET is supported, the RFC dictates that HEAD and OPTIONS must also be implicitly supported.
        if (methods.contains("GET")) {
            methods.add("HEAD");
        }
        methods.add("OPTIONS");
        return String.join(", ", methods);
    }
}