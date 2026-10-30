package com.examworkspace.monitor;

import com.examworkspace.alerts.RoomLookup;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The foreground-app check for a student who is inside a room -- ported from the
 * examworkspace-final project's {@code StudentMonitorView}, with the same rules:
 *
 * <ul>
 *   <li>An app the teacher allowed for THIS room is fine (matched by exe name, path,
 *       app name or window title -- see {@link AllowedApp}).</li>
 *   <li>This workspace app itself, and routine Windows shell UI, are always fine.</li>
 *   <li>Anything else, once it has stayed in the foreground for {@link #GRACE_MS}, is
 *       flagged: a row is written for the teacher's Live Monitor (see {@link ViolationStore}).
 *       Going back to an allowed app before that resets the timer and nothing happens.</li>
 * </ul>
 *
 * <p>The room's allowed list is re-read every {@link #REFRESH_SECONDS} seconds, so apps the
 * teacher adds or removes mid-exam take effect almost immediately. Just before flagging, the
 * list is read once more and the app re-checked, so an app the teacher allowed a moment ago
 * is never flagged.
 *
 * <p>This class has no UI. It reports through two callbacks (the caller decides how to show
 * them): a warning when an unapproved app first appears, and a notice once a flag is recorded.
 * Requires Windows; elsewhere {@link #start()} just logs that the check is unavailable.
 */
public final class StudentRoomMonitor {

    private static final long GRACE_MS = 3000;
    private static final long REFRESH_SECONDS = 2;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Windows shell UI that is never a violation (what the student-side check ignores). */
    private static final Set<String> SHELL_UI = Set.of(
            "explorer.exe", "startmenuexperiencehost.exe", "searchhost.exe", "searchapp.exe",
            "shellexperiencehost.exe", "textinputhost.exe", "lockapp.exe", "dwm.exe");

    /** The final re-check before flagging additionally ignores UWP frame hosts. */
    private static final Set<String> SHELL_UI_RECHECK = Set.of(
            "explorer.exe", "startmenuexperiencehost.exe", "searchhost.exe", "searchapp.exe",
            "shellexperiencehost.exe", "textinputhost.exe", "lockapp.exe", "dwm.exe",
            "applicationframehost.exe");

    private static final String OWN_WINDOW_TITLE = "exam workspace";

    private final long roomId;
    private final Long studentId;
    private final String fallbackName;
    private final Consumer<String> onWarning;
    private final Consumer<String> onFlagged;

    private final long ownPid = ProcessHandle.current().pid();
    private final String ownExePath = ProcessHandle.current().info().command().orElse("").toLowerCase(Locale.ROOT);

    private volatile List<AllowedApp> apps = List.of();
    private volatile RoomLookup.RoomInfo roomInfo;

    // Episode tracking: only the foreground-monitor thread reads/writes these,
    // except episodeFlagged which the flag writer resets if a write fails.
    private String episodeKey;
    private long episodeStart;
    private volatile boolean episodeFlagged;
    private String lastLoggedKey;
    private final AtomicBoolean flagInFlight = new AtomicBoolean(false);

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile ForegroundMonitor monitor;
    private volatile ScheduledExecutorService refresher;

    /**
     * @param roomId       the room the student is in
     * @param studentId    the student's account id, or null for a guest
     * @param fallbackName name to show if the join identity is unknown
     * @param onWarning    called (on a background thread) when an unapproved app first appears
     * @param onFlagged    called (on a background thread) once a flag has been recorded
     */
    public StudentRoomMonitor(long roomId, Long studentId, String fallbackName,
                              Consumer<String> onWarning, Consumer<String> onFlagged) {
        this.roomId = roomId;
        this.studentId = studentId;
        this.fallbackName = fallbackName == null || fallbackName.isBlank() ? "Student" : fallbackName;
        this.onWarning = onWarning;
        this.onFlagged = onFlagged;
        this.roomInfo = new RoomLookup.RoomInfo(roomId, "", "", List.of());
    }

    /** Starts checking in the background. Safe to call more than once. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        Thread starter = new Thread(() -> {
            refreshAllowed();           // have the room's list before the first check
            if (stopped) {
                return;
            }
            monitor = new ForegroundMonitor(this::onDetected);
            try {
                monitor.start();
            } catch (UnsatisfiedLinkError | NoClassDefFoundError err) {
                log("Foreground-app check is unavailable on this operating system (Windows is required).");
                monitor = null;
                return;
            }
            if (stopped) {
                monitor.stop();
                return;
            }
            refresher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "room-allowed-refresher");
                t.setDaemon(true);
                return t;
            });
            refresher.scheduleWithFixedDelay(this::refreshAllowed, 3, 3, TimeUnit.SECONDS);
            log("Foreground-app check started for room " + roomId + ".");
        }, "room-monitor-start");
        starter.setDaemon(true);
        starter.start();
    }

    public void stop() {
        stopped = true;
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    // ── detection (runs once per second on the foreground-monitor thread) ────

    private void onDetected(ForegroundMonitor.ActiveProcess p) {
        boolean uwpHost = p.processName().equalsIgnoreCase("ApplicationFrameHost.exe");
        String display = uwpHost && !p.windowTitle().isBlank() ? p.windowTitle() : p.processName();
        String key = p.processName().toLowerCase(Locale.ROOT) + "|" + (uwpHost ? p.windowTitle() : "");

        Optional<String> reason = allowReason(p);
        boolean allowed = reason.isPresent();

        if (!key.equals(lastLoggedKey)) {
            lastLoggedKey = key;
            if (allowed) {
                log("Foreground: " + display + "  ->  ALLOWED (" + reason.get() + ")");
            } else {
                log("Foreground: " + display + "  ->  NOT on the allowed list  [" + p.executablePath() + "]");
            }
        }

        long now = System.currentTimeMillis();
        if (allowed) {
            episodeKey = null;
            episodeFlagged = false;
            return;
        }

        if (!key.equals(episodeKey)) {
            episodeKey = key;
            episodeStart = now;
            episodeFlagged = false;
            onWarning.accept(display + " isn't approved for this room. Return to an allowed app within "
                    + (GRACE_MS / 1000) + " seconds.");
        }
        long elapsed = now - episodeStart;
        if (!episodeFlagged && elapsed >= GRACE_MS && flagInFlight.compareAndSet(false, true)) {
            episodeFlagged = true;
            log("FLAG: " + display + " is not allowed \u2014 notifying the teacher.");
            flag(display, p.executablePath());
        }
    }

    private Optional<String> allowReason(ForegroundMonitor.ActiveProcess p) {
        String exe = p.processName().toLowerCase(Locale.ROOT).trim();
        String path = p.executablePath() == null ? "" : p.executablePath().toLowerCase(Locale.ROOT).trim();

        if (p.pid() == ownPid) return Optional.of("this workspace app");
        if (!ownExePath.isEmpty() && path.equals(ownExePath)
                && p.windowTitle().toLowerCase(Locale.ROOT).contains(OWN_WINDOW_TITLE)) {
            return Optional.of("Exam Workspace window");
        }
        if (p.windowTitle().toLowerCase(Locale.ROOT).contains(OWN_WINDOW_TITLE)) {
            return Optional.of("Exam Workspace window");
        }
        if (SHELL_UI.contains(exe) || SHELL_UI.contains(exe + ".exe") || exe.equals("explorer")) {
            return Optional.of("Windows system UI");
        }

        for (AllowedApp allowedApp : apps) {
            Optional<String> match = allowedApp.match(p.processName(), p.executablePath(), p.windowTitle());
            if (match.isPresent()) {
                return Optional.of("teacher allowed \"" + allowedApp.name() + "\" \u2014 matched " + match.get());
            }
        }
        return Optional.empty();
    }

    // ── flagging ─────────────────────────────────────────────────────────────

    private void flag(String display, String path) {
        Thread worker = new Thread(() -> {
            try {
                // Pick up anything the teacher added in the last moment, then re-check.
                refreshAllowed();
                if (isAllowedAppOrShell(display, path)) {
                    log("The teacher's current list allows " + display + " \u2014 not flagging.");
                    return;
                }

                SessionIdentity.Identity who = SessionIdentity.recall(roomId);
                String name = who != null && who.displayName() != null ? who.displayName() : fallbackName;
                String roll = who != null ? who.rollNumber() : null;

                String screenshot = ScreenshotCapture.captureBase64();
                ViolationStore.record(roomId, studentId, roll, name, display, path, screenshot);
                log("Flag recorded for " + name + ": " + display
                        + (screenshot != null ? " (with screenshot)" : " (screenshot unavailable)"));
                onFlagged.accept("Your teacher has been notified that you opened " + display + ".");
            } catch (Exception ex) {
                log("Could not record the flag (" + ex.getMessage() + ") \u2014 will retry.");
                episodeFlagged = false; // the next check tries again
            } finally {
                flagInFlight.set(false);
            }
        }, "violation-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Final check against the freshest list before a flag is written. */
    private boolean isAllowedAppOrShell(String detectedApp, String executablePath) {
        String appName = detectedApp == null ? "" : detectedApp.toLowerCase(Locale.ROOT).trim();
        int sepApp = Math.max(appName.lastIndexOf('\\'), appName.lastIndexOf('/'));
        String appFile = sepApp >= 0 ? appName.substring(sepApp + 1) : appName;

        String pathName = executablePath == null ? "" : executablePath.toLowerCase(Locale.ROOT).trim();
        int sepPath = Math.max(pathName.lastIndexOf('\\'), pathName.lastIndexOf('/'));
        String pathFile = sepPath >= 0 ? pathName.substring(sepPath + 1) : pathName;

        // Windows shell UI (including explorer.exe)
        if (SHELL_UI_RECHECK.contains(appFile) || SHELL_UI_RECHECK.contains(pathFile)
                || appFile.equals("explorer") || pathFile.equals("explorer")
                || SHELL_UI_RECHECK.contains(appFile + ".exe") || SHELL_UI_RECHECK.contains(pathFile + ".exe")) {
            return true;
        }

        // The room's allowed process names
        RoomLookup.RoomInfo room = roomInfo;
        if (room.isAllowed(appFile) || room.isAllowed(appName)
                || (!pathFile.isEmpty() && room.isAllowed(pathFile))
                || (!pathName.isEmpty() && room.isAllowed(pathName))) {
            return true;
        }

        // The room's allowed apps (name / path / app id)
        for (AllowedApp app : apps) {
            if (app.match(appFile, executablePath, "").isPresent()
                    || app.match(appName, executablePath, "").isPresent()) {
                return true;
            }
        }
        return false;
    }

    // ── allowed-list refresh ─────────────────────────────────────────────────

    private void refreshAllowed() {
        try {
            RoomLookup.AllowedSnapshot snapshot = RoomLookup.loadAllowedSnapshot(roomId);
            boolean changed = !snapshot.apps().equals(apps);
            apps = List.copyOf(snapshot.apps());
            roomInfo = new RoomLookup.RoomInfo(roomId, "", "", List.copyOf(snapshot.processNames()));
            if (changed) {
                log("Allowed list: " + (snapshot.apps().isEmpty() ? "(none)"
                        : String.join(", ", snapshot.apps().stream().map(AllowedApp::describe).toList())));
            }
        } catch (Exception ignored) {
            // Keep the previous list if the database is briefly busy.
        }
    }

    private void log(String message) {
        System.out.println("[" + LocalTime.now().format(LOG_TIME) + "] [ForegroundCheck] " + message);
    }
}
