interface RouteHandler {
    HttpResponse handle(HttpRequest request) throws Exception;
}

