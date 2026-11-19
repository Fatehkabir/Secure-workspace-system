package com.examworkspace.screenshare;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Student-side TCP client that connects to the teacher's {@link ScreenShareServer}
 * and passes raw JPEG frame bytes to a listener (the UI window).
 *
 * <p>Frame protocol (matches server):
 * <pre>
 *   [4-byte int: frame length N][N bytes: JPEG data]
 * </pre>
 *
 * <p>The client runs on a background daemon thread. When the teacher stops
 * sharing (server closes), the {@code onDisconnected} callback fires so the
 * student's viewer window can close automatically.
 */
public final class ScreenShareClient {

    private static final Logger LOG = Logger.getLogger(ScreenShareClient.class.getName());

    /** Maximum frame size accepted (20 MB). Larger frames are ignored to prevent OOM. */
    private static final int MAX_FRAME_BYTES = 20 * 1024 * 1024;
    /** TCP connect timeout in milliseconds. */
    private static final int CONNECT_TIMEOUT_MS = 4000;

    private final String host;
    private final int port;
    private final Consumer<byte[]> onFrame;
    private final Runnable onDisconnected;

    private volatile Socket socket;
    private volatile boolean running;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screenshare-client");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param host           teacher's LAN IP address
     * @param port           TCP port of the teacher's server
     * @param onFrame        called on the client thread with each raw JPEG frame
     * @param onDisconnected called when the connection drops (teacher stopped sharing)
     */
    public ScreenShareClient(String host, int port,
                             Consumer<byte[]> onFrame,
                             Runnable onDisconnected) {
        this.host = host;
        this.port = port;
        this.onFrame = onFrame;
        this.onDisconnected = onDisconnected;
    }

    /** Connects and starts receiving frames in the background. */
    public void start() {
        running = true;
        executor.submit(this::receiveLoop);
    }

    /** Disconnects and stops the receive loop. */
    public void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void receiveLoop() {
        int retryCount = 0;
        final int MAX_RETRIES = 5;

        while (running && retryCount < MAX_RETRIES) {
            try {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setReceiveBufferSize(512 * 1024);
                socket.setTcpNoDelay(true);
                LOG.info("[ScreenShareClient] Connected to " + host + ":" + port);
                retryCount = 0; // reset on successful connection

                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (running) {
                    int length = in.readInt();
                    if (length <= 0 || length > MAX_FRAME_BYTES) {
                        LOG.warning("[ScreenShareClient] Skipping invalid frame length: " + length);
                        continue;
                    }
                    byte[] frame = new byte[length];
                    in.readFully(frame);
                    onFrame.accept(frame);
                }
            } catch (IOException e) {
                if (running) {
                    retryCount++;
                    LOG.info("[ScreenShareClient] Connection issue (" + e.getMessage() + "), retry " + retryCount + "/" + MAX_RETRIES);
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } finally {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException ignored) {}
            }
        }

        running = false;
        onDisconnected.run();
    }
}
