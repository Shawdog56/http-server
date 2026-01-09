import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ProxyTask implements Runnable {
    private final Socket clientSocket;
    private final int targetPort;

    public ProxyTask(Socket clientSocket, int targetPort) {
        this.clientSocket = clientSocket;
        this.targetPort = targetPort;
    }

    @Override
    public void run() {
        try (Socket serverSocket = new Socket("localhost", targetPort);
             InputStream clientIn = clientSocket.getInputStream();
             OutputStream clientOut = clientSocket.getOutputStream();
             InputStream serverIn = serverSocket.getInputStream();
             OutputStream serverOut = serverSocket.getOutputStream()) {

            // Thread to read from client and write to secondary server
            Thread t1 = new Thread(() -> pipe(clientIn, serverOut));
            // Thread to read from secondary server and write to client
            Thread t2 = new Thread(() -> pipe(serverIn, clientOut));

            t1.start();
            t2.start();
            t1.join();
            t2.join();
        } catch (Exception e) {
            System.err.println("Proxy error: " + e.getMessage());
        }
    }

    private void pipe(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {}
    }
}