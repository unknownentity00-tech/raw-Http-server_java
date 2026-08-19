import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Per-connection state; selector-owned fields are never mutated by workers. */
public final class ClientState {
    public final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    public final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    public boolean headersParsed;
    public int boundaryIndex = -1;
    public int contentLength;
    public boolean malformedRequest;
    public HttpRequest request;
    public ByteBuffer writeBuffer;
    public boolean processing;
    public boolean closed;
    public int requestsProcessed;
    public long lastActivityTime = System.currentTimeMillis();

    public void resetForNextRequest() {
        headersParsed = false;
        boundaryIndex = -1;
        contentLength = 0;
        malformedRequest = false;
        request = null;
        writeBuffer = null;
    }

    public void updateActivity() { lastActivityTime = System.currentTimeMillis(); }
}
