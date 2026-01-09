import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class CustomHttpServer {
    private final int port;
    private final int secondaryPort;
    private final int poolSize;
    private final ThreadPoolExecutor pool;
    private final String storageDir = "server_storage";
    private boolean secondaryServerStarted = false;

    public CustomHttpServer(int port, int secondaryPort, int poolSize) {
        this.port = port;
        this.secondaryPort = secondaryPort;
        this.poolSize = poolSize;
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);
        new File(storageDir).mkdir();
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port + " (Pool: " + poolSize + ")");

            while (true) {
                Socket client = serverSocket.accept();
                int activeThreads = pool.getActiveCount();

                if (activeThreads >= (poolSize / 2)) {
                    // If it's the primary server (secondaryPort > 0) and we haven't started the backup yet
                    if (secondaryPort > 0 && !secondaryServerStarted) {
                        System.out.println("Threshold reached! Dynamically starting Secondary Server on " + secondaryPort);
                        startSecondaryServer();
                        secondaryServerStarted = true;
                    }
                    
                    System.out.println("Proxying request to port " + secondaryPort);
                    new Thread(new ProxyTask(client, secondaryPort)).start();
                } else {
                    pool.execute(() -> handleClient(client));
                }
            }
        }
    }

    private void startSecondaryServer() {
        new Thread(() -> {
            try {
                // Secondary servers usually don't need their own secondary, so we pass 0
                new CustomHttpServer(secondaryPort, 0, poolSize).start();
            } catch (IOException e) {
                System.err.println("Failed to start secondary server: " + e.getMessage());
            }
        }).start();
    }

    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\r') continue; // Skip carriage returns
            if (b == '\n') break;    // Stop at the newline
            sb.append((char) b);
        }
        // If the stream ended without any characters, return null
        return (sb.length() == 0 && b == -1) ? null : sb.toString();
    }

    private void handleClient(Socket client) {
        try (InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream()) {

            // 1. Read Request Line manually from the stream
            String requestLine = readLine(is);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1].substring(1);

            // 2. Read Headers manually to avoid the Buffer Trap
            int contentLength = 0;
            String headerLine;
            while (!(headerLine = readLine(is)).isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                }
            }

            // 3. Now the 'is' (InputStream) is positioned EXACTLY at the start of the body
            switch (method) {
                case "GET" -> doGet(os, path);
                case "POST", "PUT" -> {
                    doSave(is, path, contentLength);
                    sendResponse(os, "200 OK", "text/plain", "File saved".getBytes());
                }
                case "DELETE" -> doDelete(os, path);
                default -> sendResponse(os, "405 Method Not Allowed", "text/plain", "Error".getBytes());
            }

        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void doGet(OutputStream os, String path) throws IOException {
        File file = new File(storageDir, path);
        if (!file.exists()) {
            sendResponse(os, "404 Not Found", "text/plain", "File not found".getBytes());
            return;
        }
        byte[] content = Files.readAllBytes(file.toPath());
        sendResponse(os, "200 OK", getContentType(path), content);
    }

    private void doSave(InputStream is, String path, int contentLength) throws IOException {
        System.out.println("Starting to save: " + path);
        File file = new File(storageDir, path);
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int totalRead = 0;
            int bytesRead;
            
            // Read only up to the contentLength
            while (totalRead < contentLength && 
                (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            System.out.println("Successfully saved " + totalRead + " bytes to " + path);
        }
        
    }

    private void doDelete(OutputStream os, String path) throws IOException {
        System.out.println("DELETE request handling");
        boolean deleted = Files.deleteIfExists(Paths.get(storageDir, path));
        String status = deleted ? "200 OK" : "404 Not Found";
        sendResponse(os, status, "text/plain", (deleted ? "Deleted" : "Not Found").getBytes());
    }

    private void sendResponse(OutputStream os, String status, String mime, byte[] body) throws IOException {
        PrintWriter writer = new PrintWriter(os);
        writer.println("HTTP/1.1 " + status);
        writer.println("Content-Type: " + mime);
        writer.println("Content-Length: " + body.length);
        writer.println("Connection: close");
        writer.println();
        writer.flush();
        os.write(body);
        os.flush();
    }

    private String getContentType(String path) {
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}