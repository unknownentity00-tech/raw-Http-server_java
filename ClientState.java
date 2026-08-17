import java.nio.ByteBuffer;

public class ClientState {
    //4KB buffer per client. This persists across multiple OP_READ events.
    public final ByteBuffer buffer = ByteBuffer.allocate(4096); 
}

