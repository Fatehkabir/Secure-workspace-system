package com.examworkspace.screenshare;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Captures the teacher's primary screen at a fixed frame rate and broadcasts
 * each frame as a compressed JPEG via a {@link ScreenShareServer}.
 *
 * <p>Frame rate and JPEG quality are tuned for LAN use: 5 fps at 60% quality
 * keeps bandwidth low (~100–300 KB/s) while still being smooth enough for
 * a teacher demonstrating a technique or showing exam instructions.
 */
public final class ScreenCaptureBroadcaster {

    private static final Logger LOG = Logger.getLogger(ScreenCaptureBroadcaster.class.getName());

    /** Frames per second sent to students. */
    private static final int FPS = 5;
    /** JPEG quality: 0.0 (worst) – 1.0 (best). 0.60 is a good LAN balance. */
    private static final float JPEG_QUALITY = 0.60f;

    private final ScreenShareServer server;
    private final ScheduledExecutorService scheduler;
    private volatile Robot robot;
    private volatile boolean running;

    public ScreenCaptureBroadcaster(ScreenShareServer server) {
        this.server = server;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "screenshare-capture");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts capturing and broadcasting frames.
     *
     * @throws AWTException if screen capture is unavailable on this platform
     */
    public void start() throws AWTException {
        robot = new Robot();
        running = true;
        long intervalMs = 1000L / FPS;
        scheduler.scheduleAtFixedRate(this::captureAndBroadcast, 0, intervalMs, TimeUnit.MILLISECONDS);
        LOG.info("[ScreenCaptureBroadcaster] Started at " + FPS + " fps.");
    }

    /** Stops capturing frames. */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        LOG.info("[ScreenCaptureBroadcaster] Stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void captureAndBroadcast() {
        if (!running) {
            return;
        }
        try {
            // Capture the full primary screen
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage frame = robot.createScreenCapture(screen);

            byte[] jpeg = encodeJpeg(frame);
            server.broadcast(jpeg);
        } catch (Exception e) {
            if (running) {
                LOG.warning("[ScreenCaptureBroadcaster] Capture error: " + e.getMessage());
            }
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(128 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
