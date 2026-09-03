/** Backwards-compatible entry point for the NIO server. */
public final class HttpServer {
    private HttpServer() { }
    public static void main(String[] args) { NioHttpServer.main(args); }
}
