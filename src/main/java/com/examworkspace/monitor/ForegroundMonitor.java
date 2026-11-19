package com.examworkspace.monitor;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls the Windows foreground window once per second and reports the
 * active process to a listener. Runs on its own background thread.
 *
 * Requires Windows -- {@code User32} calls only work there. On any other OS
 * {@link #start()} will throw {@link UnsatisfiedLinkError}, which callers
 * should catch and report as "monitoring unavailable on this OS".
 */
public class ForegroundMonitor {

    /**
     * @param processName    executable file name, e.g. "chrome.exe"
     * @param executablePath full path of the executable
     * @param pid            process id
     * @param windowTitle    title of the foreground window
     * @param windowClass    Win32 window class (e.g. "Chrome_WidgetWin_1",
     *                       "Windows.UI.Core.CoreWindow") -- used to tell real app
     *                       windows apart from shell surfaces (taskbar, Start,
     *                       notification flyouts)
     */
    public record ActiveProcess(String processName, String executablePath, long pid,
                                String windowTitle, String windowClass) {}

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

        String windowClass = "";
        try {
            char[] classBuffer = new char[256];
            int classLength = User32.INSTANCE.GetClassName(hwnd, classBuffer, classBuffer.length);
            windowClass = classLength > 0 ? new String(classBuffer, 0, classLength) : "";
        } catch (Throwable ignored) {
            // Older JNA builds without GetClassName: classification simply skips that signal.
        }

        final String fClass = windowClass;
        String path = ProcessHandle.of(pid).flatMap(handle -> handle.info().command()).orElse(null);
        if (path == null || path.isBlank()) {
            path = getProcessPathNative(pid);
        }

        if (path == null || path.isBlank()) {
            if (title.isBlank() && windowClass.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ActiveProcess("unknown-" + pid + ".exe", "", pid, title, fClass));
        }

        int sep = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        String fileName = sep >= 0 ? path.substring(sep + 1) : path;
        return Optional.of(new ActiveProcess(fileName, path, pid, title, fClass));
    }

    private static String getProcessPathNative(int pid) {
        try {
            HANDLE process = Kernel32.INSTANCE.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid);
            if (process != null) {
                try {
                    char[] nameBuf = new char[1024];
                    IntByReference size = new IntByReference(nameBuf.length);
                    if (Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, nameBuf, size)) {
                        return new String(nameBuf, 0, size.getValue());
                    }
                } finally {
                    Kernel32.INSTANCE.CloseHandle(process);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
