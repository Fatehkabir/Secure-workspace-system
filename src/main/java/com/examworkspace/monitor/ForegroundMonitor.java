package com.examworkspace.monitor;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public class ForegroundMonitor {

    public record ActiveProcess(String processName, String executablePath, long pid, String windowTitle) {}

    private static final long POLL_INTERVAL_MS = 1000;

    private final Consumer<ActiveProcess> onDetected;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foreground-monitor");
        t.setDaemon(true);
        return t;
    });

    public ForegroundMonitor(Consumer<ActiveProcess> onDetected) {
        this.onDetected = onDetected;
    }

    /** @throws UnsatisfiedLinkError if not running on Windows. */
    public void start() {
        // Force the native call once, synchronously, so an unsupported-OS
        // failure surfaces immediately to the caller instead of silently
        // failing inside the background thread.
        User32.INSTANCE.GetForegroundWindow();
        scheduler.scheduleAtFixedRate(this::checkOnce, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void checkOnce() {
        try {
            getActiveProcess().ifPresent(onDetected);
        } catch (Exception e) {
            System.err.println("ForegroundMonitor error: " + e.getMessage());
        }
    }

    private Optional<ActiveProcess> getActiveProcess() {
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return Optional.empty();

        IntByReference pidRef = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
        int pid = pidRef.getValue();
        if (pid <= 0) return Optional.empty();

        char[] buffer = new char[512];
        int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        String title = length > 0 ? new String(buffer, 0, length) : "";

        return ProcessHandle.of(pid).flatMap(handle -> {
            String path = handle.info().command().orElse(null);
            if (path == null) return Optional.empty();
            int sep = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            String fileName = sep >= 0 ? path.substring(sep + 1) : path;
            return Optional.of(new ActiveProcess(fileName, path, pid, title));
        });
    }
}
