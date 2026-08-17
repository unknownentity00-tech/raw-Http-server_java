import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ClientState {
    // 1. The OS Interface
    // The temporary landing zone for bytes arriving during a single OP_READ event.
    public final ByteBuffer readBuffer = ByteBuffer.allocate(8192);

    // 2. The Persistent Memory
    // Safely accumulates fragments across multiple OP_READ events without complex ByteBuffer resizing math.
    public final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    // 3. The State Machine Trackers
    // These dictate whether we are hunting for \r\n\r\n, calculating payload size, or finished reading.
    public boolean headersParsed = false;
    public int boundaryIndex = -1;
    public int  contentLength = 0;// Default to 0, safer than Integer wrapper
    // NEW: The definitive flag signaling the request is ready for processing
    public boolean malformedRequest = false;
     public boolean requestComplete = false;
     public HttpRequest request = null;
    // 4. The Outbound Queue
    // Once the request is fully assembled and parsed, the response bytes are placed here for OP_WRITE.
    public ByteBuffer writeBuffer = null;

// 5. Connection Lifecycle
    public boolean keepAlive = true;

    public void reset() {
        this.headersParsed = false;
        this.boundaryIndex = -1;
        this.contentLength = 0;
        this.requestComplete = false;
        this.malformedRequest = false;
        this.request = null;
        this.writeBuffer = null;
        this.keepAlive = true;
        
        // THE PIPELINING TRAP:
        // We do NOT blindly do `accumulator.reset()` here.
        // If a client sent Request 1 and Request 2 back-to-back in the same TCP packet,
        // the accumulator already contains the bytes for Request 2! 
        // We will need to slice the accumulator safely in the Reactor loop.
    }
}
/*
readBuffer: We must have a fixed ByteBuffer for channel.read(buffer) to use. But ByteBuffer has strict capacity limits and position trackers that are easily corrupted across multiple fragmented reads.

accumulator: We extract the bytes from readBuffer immediately after every read and append them here. This gives us a dynamically expanding memory block that perfectly preserves the raw binary data.

State Trackers: We persist headersParsed and contentLength at the object level so that when OP_READ fires for the 3rd or 4th time on the same socket, the server instantly knows exactly where it left off.

writeBuffer: NIO is symmetrical. Just like we cannot guarantee we read everything in one pass, we cannot guarantee we can write everything in one pass. The OP_WRITE event will need this buffer to track how much of the response has been sent to the network card.
*/
