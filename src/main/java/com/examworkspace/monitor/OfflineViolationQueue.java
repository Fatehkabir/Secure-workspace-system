package com.examworkspace.monitor;

import com.examworkspace.db.Database;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Persists violations to local disk when the student's PC is offline or the central
 * database is temporarily unreachable.
 *
 * <p>As soon as network connectivity to the database is restored, the queue worker
 * automatically transmits pending violations to the teacher's Live Monitor, preserving
 * the exact timestamp and full screenshot.
 */
public final class OfflineViolationQueue {

    private static final String DIR_NAME = "offline_violations";
    private static ScheduledExecutorService syncWorker;
    private static boolean workerStarted = false;

    private OfflineViolationQueue() {
    }

    public static synchronized void startWorker() {
        if (workerStarted) {
            return;
        }
        workerStarted = true;
        syncWorker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "offline-violation-syncer");
            t.setDaemon(true);
            return t;
        });
        syncWorker.scheduleWithFixedDelay(OfflineViolationQueue::drainPending, 3, 5, TimeUnit.SECONDS);
    }

    public static Path queueDir() {
        return Database.dataDir().resolve(DIR_NAME);
    }

    /**
     * Enqueues a violation to disk for subsequent transmission.
     */
    public static void enqueue(long roomId, Long studentId, String rollNumber, String studentName,
                               String detectedApp, String executablePath, String screenshotBase64) {
        startWorker();
        Path dir = queueDir();
        try {
            Files.createDirectories(dir);
            long now = System.currentTimeMillis();
            Path file = dir.resolve("v_" + roomId + "_" + now + "_" + System.nanoTime() + ".dat");

            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(String.valueOf(roomId));
                w.newLine();
                w.write(studentId == null ? "" : String.valueOf(studentId));
                w.newLine();
                w.write(rollNumber == null ? "" : rollNumber);
                w.newLine();
                w.write(studentName == null ? "" : studentName);
                w.newLine();
                w.write(detectedApp == null ? "" : detectedApp);
                w.newLine();
                w.write(executablePath == null ? "" : executablePath);
                w.newLine();
                w.write(String.valueOf(now));
                w.newLine();
                w.write(screenshotBase64 == null ? "" : screenshotBase64);
                w.newLine();
            }
            System.out.println("[OfflineViolationQueue] Saved violation locally for " + studentName + ": " + detectedApp);
        } catch (IOException e) {
            System.err.println("[OfflineViolationQueue] Failed to save offline violation: " + e.getMessage());
        }
    }

    /**
     * Attempts to drain pending offline files to the database.
     */
    public static void drainPending() {
        Path dir = queueDir();
        if (!Files.exists(dir)) {
            return;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().startsWith("v_") && p.getFileName().toString().endsWith(".dat"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            return;
        }

        if (files.isEmpty()) {
            return;
        }

        // Test connectivity first
        try (Connection conn = Database.connect()) {
            if (conn.isClosed()) {
                return;
            }
        } catch (Exception e) {
            // Still offline
            return;
        }

        for (Path file : files) {
            try {
                PendingViolation pv = readViolation(file);
                if (pv != null) {
                    ViolationStore.record(pv.roomId(), pv.studentId(), pv.rollNumber(), pv.studentName(),
                            pv.detectedApp(), pv.executablePath(), pv.occurredAt(), pv.screenshotBase64());
                    try {
                        LockStore.lock(pv.roomId(), pv.studentName(), pv.detectedApp());
                    } catch (Exception ignored) {
                    }
                    System.out.println("[OfflineViolationQueue] Successfully synced offline violation to database: "
                            + pv.studentName() + " - " + pv.detectedApp());
                }
                Files.deleteIfExists(file);
            } catch (Exception e) {
                System.err.println("[OfflineViolationQueue] Failed to sync " + file.getFileName() + ": " + e.getMessage());
                break; // Stop and retry on next tick
            }
        }
    }

    private static PendingViolation readViolation(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line1 = r.readLine();
            if (line1 == null) return null;
            long roomId = Long.parseLong(line1.trim());

            String line2 = r.readLine();
            Long studentId = (line2 != null && !line2.isBlank()) ? Long.parseLong(line2.trim()) : null;

            String roll = r.readLine();
            String name = r.readLine();
            String app = r.readLine();
            String exePath = r.readLine();

            String line7 = r.readLine();
            long occurredAt = (line7 != null && !line7.isBlank()) ? Long.parseLong(line7.trim()) : System.currentTimeMillis();

            String screenshot = r.readLine();
            return new PendingViolation(roomId, studentId, roll, name, app, exePath, occurredAt, screenshot);
        }
    }

    public record PendingViolation(long roomId, Long studentId, String rollNumber, String studentName,
                                   String detectedApp, String executablePath, long occurredAt, String screenshotBase64) {}
}
