import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ClientWorker implements Runnable  {
      
    private final Socket clientSocket;

    public ClientWorker(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        // We move the try-with-resources here so the thread closes the socket when done
        try (clientSocket; 
             InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            // --- PASTE ALL THE PARSER AND RESPONSE LOGIC HERE ---
            HttpParser parser = new HttpParser();
            HttpRequest request = parser.readRequest(input);
            
            // ... printing logic ...
            // ... response logic ...

        } catch (IOException e) {
            System.err.println("Worker thread caught error: " + e.getMessage());
        }
    }
}