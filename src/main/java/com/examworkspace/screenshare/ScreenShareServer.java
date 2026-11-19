package com.examworkspace.screenshare;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Lightweight TCP server embedded in the teacher's application.
 * Accepts connections from student clients and broadcasts JPEG frame bytes to all of them.
 *
 * <p>Frame protocol (little-endian):
 * <pre>
 *   [4-byte int: frame length N][N bytes: JPEG data]
 * </pre>
 * Clients that fall behind (slow network or slow UI) are disconnected silently
 * so they never stall the broadcast loop.
 */
public final class ScreenShareServer {

    private static final Logger LOG = Logger.getLogger(ScreenShareServer.class.getName());
    static final int DEFAULT_PORT = 8766;

    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    private final CopyOnWriteArrayList<DataOutputStream> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService acceptPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screenshare-accept");
        t.setDaemon(true);
        return t;
    });

    public ScreenShareServer() {
        this(DEFAULT_PORT);
    }

    public ScreenShareServer(int port) {
        this.port = port;
    }

    /**
     * Starts the server and begins accepting client connections.
     *
     * @throws IOException if the port cannot be bound
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptPool.submit(this::acceptLoop);
        LOG.info("[ScreenShareServer] Listening on port " + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                // Large send buffer to absorb bursts without blocking the broadcast thread
                client.setSendBufferSize(512 * 1024);
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                clients.add(out);
                LOG.info("[ScreenShareServer] Client connected: " + client.getRemoteSocketAddress()
                        + "  (total=" + clients.size() + ")");
            } catch (IOException e) {
                if (running) {
                    LOG.warning("[ScreenShareServer] Accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Broadcasts a single JPEG frame to every connected client.
     * Clients that have disconnected or errored are removed automatically.
     *
     * @param jpegBytes compressed JPEG frame data
     */
    public void broadcast(byte[] jpegBytes) {
        if (!running || clients.isEmpty()) {
            return;
        }
        for (DataOutputStream out : clients) {
            try {
                out.writeInt(jpegBytes.length);
                out.write(jpegBytes);
                out.flush();
            } catch (IOException e) {
                // Client disconnected — remove it
                clients.remove(out);
            }
        }
    }

    /** Returns the number of currently connected student clients. */
    public int clientCount() {
        return clients.size();
    }

    /** Returns the TCP port this server is bound to. */
    public int getPort() {
        return port;
    }

    /** Stops the server and disconnects all clients. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        clients.clear();
        acceptPool.shutdownNow();
        LOG.info("[ScreenShareServer] Stopped.");
    }
}
