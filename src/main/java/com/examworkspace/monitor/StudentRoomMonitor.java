package com.examworkspace.monitor;

import com.examworkspace.alerts.RoomLookup;
import com.examworkspace.browser.BrowserProcesses;
import com.examworkspace.browser.BrowserUrlAgent;
import com.examworkspace.browser.UrlAllowlistPolicy;
import com.examworkspace.room.RoomUrlPolicyService;

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

    private static final long GRACE_MS = 5000;
    /**
     * Extra-long grace used while a browser is in the foreground but its address
     * bar cannot be read yet (the URL helper may still be starting up). Strict
     * mode flags the browser after this window too, because an un-allowed
     * browser whose page is unknown may be on any website.
     */
    private static final long UNREADABLE_GRACE_MS = 2 * GRACE_MS;
    private static final long REFRESH_SECONDS = 2;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Windows shell UI, system utilities, settings, network, and battery/power alerts that are never a violation. */
    private static final Set<String> SYSTEM_SHELL_PROCESSES = Set.of(
            // Windows Shell & Desktop
            "explorer.exe", "explorer",
            "dwm.exe",
            "lockapp.exe",
            "shellexperiencehost.exe",
            "startmenuexperiencehost.exe",
            "searchhost.exe",
            "searchapp.exe",
            "textinputhost.exe",
            "notificationhost.exe",
            "actioncenter.exe",
            "ctfmon.exe",
            "tabtip.exe",
            "taskhostw.exe",
            "svchost.exe",
            "sihost.exe",
            "openwith.exe",
            "pickerhost.exe",
            "splwow64.exe",

            // Windows Settings & Network / Wi-Fi
            "systemsettings.exe",
            "systemsettingsadminflows.exe",
            "systemsettingsbroker.exe",
            "systemsettingsthreshold.exe",
            "control.exe",
            "rundll32.exe",
            "van.exe",
            "credentialuibroker.exe",
            "consent.exe",
            "smartscreen.exe",
            "securityhealthsystray.exe",
            "securityhealthhost.exe",
            "deviceflows.exe",
            "displayswitch.exe",

            // Audio, Power & Battery
            "sndvol.exe",
            "sndvolsso.exe",
            "powercfg.exe",
            "battstat.exe",

            // OEM Laptop Battery, Power & System Utilities (HP, Lenovo, Dell, Asus, Acer)
            "hpbatteryalert.exe",
            "hp.powerservice.exe",
            "hpsysinfo.exe",
            "caslfeedback.exe",
            "hpsupportassistant.exe",
            "hposd.exe",
            "hpqwmiex.exe",
            "hpcommrecovery.exe",
            "lenovovantage.exe",
            "lenovopowerservice.exe",
            "lenovohotkeys.exe",
            "dellpowermanager.exe",
            "dellsupportassist.exe",
            "asuspower.exe",
            "asusoptimization.exe",
            "acercarecenter.exe",

            // ── Windows pop-ups & notifications that must NEVER be violations ──
            // Toasts, flyouts, overlays and helper hosts that briefly take the
            // foreground without the student opening anything.
            "runtimebroker.exe",            // UWP permission/ broker pop-ups
            "backgroundtaskhost.exe",       // UWP background tasks surfacing toasts
            "msedgewebview2.exe",           // hosts Widgets, Copilot, Phone Link, Teams toasts
            "widgets.exe",
            "widgetboard.exe",              // Windows 11 Widgets board
            "widgetservice.exe",
            "phoneexperiencehost.exe",      // Phone Link pop-ups
            "yourphone.exe",
            "gamebar.exe",                  // Xbox Game Bar overlay
            "gamebarftserver.exe",
            "gamebarpresencewriter.exe",
            "musnotification.exe",          // Windows Update toast pop-ups
            "musnotifyicon.exe",
            "usoclient.exe",
            "wuauclt.exe",
            "wuapihost.exe",
            "onedrive.exe",                 // OneDrive sync pop-ups
            "audiodg.exe",                  // audio flyout helper
            "audiodevicedll.exe",
            "aggregatorhost.exe",           // Windows 11 shell host
            "wmiprvse.exe",
            "dllhost.exe",
            "conhost.exe",
            "msmpeng.exe",                  // Defender background pop-ups
            "nissrv.exe",
            "securityhealthservice.exe",
            "nvcontainer.exe"               // NVIDIA overlay notifications
    );

    /**
     * Win32 window classes that belong to Windows shell surfaces (taskbar,
     * desktop, notification overflow) rather than to a real application window.
     * A foreground window with one of these classes is never a violation.
     */
    private static final Set<String> NEUTRAL_WINDOW_CLASSES = Set.of(
            "shell_traywnd",            // taskbar
            "shell_secondarytraywnd",   // secondary monitor taskbar
            "progman",                  // desktop
            "workerw",                  // desktop wallpaper layer
            "notifyiconoverflowwindow", // system tray overflow flyout
            "toplevelwindowforoverflowxamlisland", // Win11 tray overflow
            "xamlexplorerhostislandwindow"          // Win11 shell island
    );
    /** UWP shell surface class; only neutral when the title is blank or a system alert. */
    private static final String CORE_WINDOW_CLASS = "windows.ui.core.corewindow";

    private static final String OWN_WINDOW_TITLE = "exam workspace";

    private final long roomId;
    private final Long studentId;
    private final String fallbackName;
    private final Consumer<String> onWarning;
    private final Consumer<String> onFlagged;
    private final Consumer<String> onLocked;

    private final long ownPid = ProcessHandle.current().pid();
    private final String ownExePath = ProcessHandle.current().info().command().orElse("").toLowerCase(Locale.ROOT);

    private volatile List<AllowedApp> apps = List.of();
    private volatile RoomLookup.RoomInfo roomInfo;
    /** The room's website allowlist; re-read together with the allowed apps. */
    private volatile UrlAllowlistPolicy urlPolicy = UrlAllowlistPolicy.disabled();

    // Episode tracking: only the foreground-monitor thread reads/writes these,
    // except episodeFlagged which the flag writer resets if a write fails.
    private String episodeKey;
    private long episodeStart;
    private volatile boolean episodeFlagged;
    private String lastLoggedKey;
    private String lastUnreadableKey;
    private final AtomicBoolean flagInFlight = new AtomicBoolean(false);

    /**
     * Last URL that was positively confirmed as ALLOWED or IDLE for a browser
     * process, tracked by (processName → timestamp). While a browser is
     * navigating, its address bar goes blank or unreadable for 1–3 seconds.
     * During that window we keep treating it as allowed instead of punishing
     * the student for a normal page load. The allowed state expires after
     * NAV_FORGIVENESS_MS so a student who closes the allowed tab and sits on
     * a forbidden page is still eventually flagged.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastAllowedBrowserMs
            = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long (ms) a browser that was last on an allowed page is forgiven while its URL is unreadable. */
    private static final long NAV_FORGIVENESS_MS = 15000;

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile boolean locked;
    private volatile ForegroundMonitor monitor;
    private volatile ScheduledExecutorService refresher;

    /**
     * @param roomId       the room the student is in
     * @param studentId    the student's account id, or null for a guest
     * @param fallbackName name to show if the join identity is unknown
     * @param onWarning    called (on a background thread) when an unapproved app first appears
     * @param onFlagged    called (on a background thread) once a flag has been recorded
     * @param onLocked     called (on a background thread) the instant the workspace is locked,
     *                     with the app name that triggered it -- the caller should show a
     *                     blocking lock screen immediately rather than waiting for a poll
     */
    public StudentRoomMonitor(long roomId, Long studentId, String fallbackName,
                              Consumer<String> onWarning, Consumer<String> onFlagged, Consumer<String> onLocked) {
        this.roomId = roomId;
        this.studentId = studentId;
        this.fallbackName = fallbackName == null || fallbackName.isBlank() ? "Student" : fallbackName;
        this.onWarning = onWarning;
        this.onFlagged = onFlagged;
        this.onLocked = onLocked;
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

    /** What the website allowlist says about the foreground browser. */
    private enum UrlState {
        /** Address bar shows a teacher-approved page. */
        ALLOWED,
        /** Browser is on an internal page (new tab, blank) -- not a website, never flagged. */
        IDLE,
        /** Address bar shows a page the teacher did not allow -- a violation. */
        VIOLATION,
        /** The URL could not be read right now -- deliberately never flagged. */
        UNREADABLE,
        /** Not a browser, or the room has no website allowlist. */
        NOT_APPLICABLE
    }

    private record UrlDecision(UrlState state, String reason, String url) {
        static final UrlDecision NOT_APPLICABLE = new UrlDecision(UrlState.NOT_APPLICABLE, "", "");
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isLocked() {
        return locked;
    }

    private void onDetected(ForegroundMonitor.ActiveProcess p) {
        if (locked || LockStore.hasLocalLock(roomId, fallbackName)) {
            return;
        }
        boolean uwpHost = p.processName().equalsIgnoreCase("ApplicationFrameHost.exe");
        String display = uwpHost && !p.windowTitle().isBlank() ? p.windowTitle() : p.processName();

        Optional<String> reason = allowReason(p);
        boolean allowed = reason.isPresent();

        // Website allowlist: for a browser the page being viewed decides -- even when
        // the browser app itself is on the allowed list. Opening an approved site is
        // fine; surfing anywhere else is not.
        UrlDecision decision = UrlDecision.NOT_APPLICABLE;
        if (urlPolicy.enabled() && BrowserProcesses.isBrowser(p.processName())) {
            decision = decideByUrl(p);
            String browserKey = p.processName().toLowerCase(Locale.ROOT);
            switch (decision.state()) {
                case ALLOWED -> {
                    // Record that this browser was last seen on an allowed page right now.
                    lastAllowedBrowserMs.put(browserKey, System.currentTimeMillis());
                    allowed = true;
                    reason = Optional.of(decision.reason());
                }
                case IDLE -> {
                    // Internal pages (new-tab, blank) during navigation — treat as allowed
                    // but still update the forgiveness timestamp so the student isn't punished
                    // for the brief chrome://newtab that appears between page loads.
                    lastAllowedBrowserMs.put(browserKey, System.currentTimeMillis());
                    allowed = true;
                    reason = Optional.of("browser on an internal page (no website open)");
                }
                case UNREADABLE -> {
                    // Address bar is momentarily blank (page is loading / redirecting).
                    // If this browser was confirmed on an allowed page within the forgiveness
                    // window, keep treating it as allowed so normal in-site navigation never
                    // triggers a false violation.
                    Long lastAllowed = lastAllowedBrowserMs.get(browserKey);
                    boolean withinForgivenessWindow = lastAllowed != null
                            && (System.currentTimeMillis() - lastAllowed) <= NAV_FORGIVENESS_MS;
                    if (withinForgivenessWindow) {
                        allowed = true;
                        reason = Optional.of("browser navigating (last confirmed page was allowed)");
                    } else if (!allowed) {
                        // STRICT MODE: browser not on allowed-app list and no recent allowed
                        // page — flag after the extended grace period.
                        warnOnceUnreadable(p.processName());
                    }
                }
                case VIOLATION -> {
                    // Clear the forgiveness timestamp — student is definitely on a bad page.
                    lastAllowedBrowserMs.remove(browserKey);
                    allowed = false;
                    display = p.processName() + " \u2014 " + decision.reason();
                }
                case NOT_APPLICABLE -> { }
            }
        }

        String key = p.processName().toLowerCase(Locale.ROOT) + "|" + (uwpHost ? p.windowTitle() : "")
                + "|" + switch (decision.state()) {
            case VIOLATION, ALLOWED -> decision.url();
            default -> "";
        };

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
        long grace = decision.state() == UrlState.UNREADABLE ? UNREADABLE_GRACE_MS : GRACE_MS;
        long elapsed = now - episodeStart;
        if (!episodeFlagged && elapsed >= grace && flagInFlight.compareAndSet(false, true)) {
            episodeFlagged = true;
            log("FLAG: " + display + " is not allowed \u2014 notifying the teacher.");
            flag(p, display);
        }
    }

    /**
     * Reads the real address-bar URL of the foreground browser and compares it
     * with the room's website allowlist.
     */
    public boolean isWindowTitleAllowed(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank() || urlPolicy == null || !urlPolicy.enabled()) {
            return false;
        }
        String titleLower = windowTitle.toLowerCase(Locale.ROOT);
        for (UrlAllowlistPolicy.Rule rule : urlPolicy.rules()) {
            String match = rule.match();
            if (match == null || match.isBlank()) continue;
            String host = match;
            if (host.contains("://")) {
                try {
                    java.net.URI u = new java.net.URI(host);
                    if (u.getHost() != null) host = u.getHost();
                } catch (Exception ignored) {}
            }
            int slash = host.indexOf('/');
            if (slash >= 0) host = host.substring(0, slash);
            String root = UrlAllowlistPolicy.rootDomain(host);
            if (!root.isBlank() && titleLower.contains(root.toLowerCase(Locale.ROOT))) {
                return true;
            }
            String name = root;
            int dot = name.indexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            if (name.length() >= 3 && titleLower.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private UrlDecision decideByUrl(ForegroundMonitor.ActiveProcess p) {
        BrowserUrlAgent agent = BrowserUrlAgent.get();
        agent.start();
        String raw = agent.currentUrl(p.pid(), p.processName()).orElse("");
        if (raw.isBlank()) {
            if (isWindowTitleAllowed(p.windowTitle())) {
                return new UrlDecision(UrlState.ALLOWED, "window title confirms allowed website (" + p.windowTitle() + ")", "");
            }
            return new UrlDecision(UrlState.UNREADABLE, "", "");
        }
        String url = BrowserProcesses.withScheme(raw);
        if (BrowserProcesses.isInternalUrl(url)) {
            return new UrlDecision(UrlState.IDLE, "", url);
        }
        if (!BrowserProcesses.looksLikeUrl(url)) {
            if (isWindowTitleAllowed(p.windowTitle())) {
                return new UrlDecision(UrlState.ALLOWED, "window title confirms allowed website (" + p.windowTitle() + ")", "");
            }
            return new UrlDecision(UrlState.UNREADABLE, "", url);
        }
        if (UrlAllowlistPolicy.isAllowed(url, urlPolicy)) {
            return new UrlDecision(UrlState.ALLOWED, "page is on the teacher's website allowlist", url);
        }
        if (isWindowTitleAllowed(p.windowTitle())) {
            return new UrlDecision(UrlState.ALLOWED, "window title confirms allowed website (" + p.windowTitle() + ")", url);
        }
        String host = url;
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getHost() != null) host = uri.getHost();
        } catch (Exception ignored) { }
        return new UrlDecision(UrlState.VIOLATION, host, url);
    }

    /** Toast at most once per browser episode when the page address cannot be read. */
    private void warnOnceUnreadable(String processName) {
        String k = processName.toLowerCase(Locale.ROOT);
        if (k.equals(lastUnreadableKey)) {
            return;
        }
        lastUnreadableKey = k;
        log("Address bar not readable for " + processName + " \u2014 strict mode still flags after "
                + (UNREADABLE_GRACE_MS / 1000) + "s (" + BrowserUrlAgent.get().stateForLog() + ")");
        onWarning.accept(processName + " isn't approved for this room and its page address could not be "
                + "read. Return to an allowed app within " + (UNREADABLE_GRACE_MS / 1000)
                + " seconds, or this will be reported.");
    }

    public static boolean isSystemAlertTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("battery")
                || t.contains("power")
                || t.contains("wi-fi")
                || t.contains("wifi")
                || t.contains("network")
                || t.contains("connect to")
                || t.contains("security key")
                || t.contains("windows security")
                || t.contains("quick settings")
                || t.contains("action center")
                || t.contains("volume")
                || t.contains("brightness")
                || t.contains("notification")
                || t.contains("new notification")
                || t.contains("notification center")
                || t.contains("date and time")
                || t.contains("battery saver")
                || t.contains("low battery")
                || t.contains("charging")
                || t.contains("windows update")
                || t.contains("restart required")
                || t.contains("microsoft defender")
                || t.contains("safely remove");
    }

    public static boolean isSystemUiOrAlert(String exe, String path, String title) {
        return isSystemUiOrAlert(exe, path, title, "");
    }

    /** Same, with the Win32 window class when the caller knows it. */
    public static boolean isSystemUiOrAlert(String exe, String path, String title, String windowClass) {
        if (exe == null) exe = "";
        if (path == null) path = "";
        if (title == null) title = "";

        String e = exe.toLowerCase(Locale.ROOT).trim();
        String p = path.toLowerCase(Locale.ROOT).trim();
        String t = title.toLowerCase(Locale.ROOT).trim();
        String c = windowClass == null ? "" : windowClass.toLowerCase(Locale.ROOT).trim();

        String cleanExe = e.endsWith(".exe") ? e : e + ".exe";
        if (SYSTEM_SHELL_PROCESSES.contains(cleanExe) || SYSTEM_SHELL_PROCESSES.contains(e)) {
            return true;
        }

        // Shell surfaces (taskbar, desktop, tray flyouts) are never violations.
        if (NEUTRAL_WINDOW_CLASSES.contains(c)) {
            return true;
        }
        // UWP shell surfaces (Start, Search, toasts, input flyouts) share one window
        // class with a few real apps, so only trust it when the title agrees.
        if (c.equals(CORE_WINDOW_CLASS) && (t.isBlank() || isSystemAlertTitle(t))) {
            return true;
        }

        // If the process is a Windows system binary or an OEM hardware utility,
        // and the window title is a system alert (battery, network, etc.):
        boolean isWindowsOrOemPath = p.contains("\\windows\\")
                || p.contains("/windows/")
                || p.contains("\\hp\\")
                || p.contains("\\lenovo\\")
                || p.contains("\\dell\\")
                || p.contains("\\asus\\")
                || p.contains("\\acer\\");

        if (isWindowsOrOemPath && isSystemAlertTitle(t)) {
            return true;
        }

        // ApplicationFrameHost hosting Settings, Network, Wi-Fi, or Battery
        if (cleanExe.equals("applicationframehost.exe")) {
            if (t.isBlank() || isSystemAlertTitle(t) || t.contains("settings")) {
                return true;
            }
        }

        return false;
    }

    private Optional<String> allowReason(ForegroundMonitor.ActiveProcess p) {
        String exe = p.processName().toLowerCase(Locale.ROOT).trim();
        String path = p.executablePath() == null ? "" : p.executablePath().toLowerCase(Locale.ROOT).trim();
        String title = p.windowTitle() == null ? "" : p.windowTitle().toLowerCase(Locale.ROOT).trim();

        if (p.pid() == ownPid) return Optional.of("this workspace app");
        if (!ownExePath.isEmpty() && path.equals(ownExePath)
                && title.contains(OWN_WINDOW_TITLE)) {
            return Optional.of("Exam Workspace window");
        }
        if (title.contains(OWN_WINDOW_TITLE)) {
            return Optional.of("Exam Workspace window");
        }
        if (isSystemUiOrAlert(exe, path, title, p.windowClass())) {
            return Optional.of("Windows system UI or alert");
        }

        // Direct check against the room's allowed process names
        RoomLookup.RoomInfo room = roomInfo;
        if (room != null && (room.isAllowed(exe) || (!path.isEmpty() && room.isAllowed(path)))) {
            if (!ProcessSecurityValidator.isRenamedImposter(p.processName(), p.executablePath(), null)) {
                return Optional.of("room allows process " + exe);
            }
        }

        for (AllowedApp allowedApp : apps) {
            Optional<String> match = allowedApp.match(p.processName(), p.executablePath(), p.windowTitle());
            if (match.isPresent()) {
                if (ProcessSecurityValidator.isRenamedImposter(p.processName(), p.executablePath(), allowedApp)) {
                    log("SECURITY ALERT: " + p.processName() + " [" + p.executablePath() + "] is an IMPOSTER (renamed unallowed executable). Blocking.");
                    continue;
                }
                return Optional.of("teacher allowed \"" + allowedApp.name() + "\" \u2014 matched " + match.get());
            }
        }
        return Optional.empty();
    }

    // ── flagging ─────────────────────────────────────────────────────────────

    private void flag(ForegroundMonitor.ActiveProcess p, String display) {
        Thread worker = new Thread(() -> {
            try {
                // Pick up anything the teacher added in the last moment, then re-check.
                refreshAllowed();
                if (isAllowedAppOrShell(p, display)) {
                    log("The teacher's current list allows " + display + " \u2014 not flagging.");
                    return;
                }

                SessionIdentity.Identity who = SessionIdentity.recall(roomId);
                String name = who != null && who.displayName() != null ? who.displayName() : fallbackName;
                String roll = who != null ? who.rollNumber() : null;

                String screenshot = ScreenshotCapture.captureBase64();

                // 1. Lock the student app IMMEDIATELY on the client device
                this.locked = true;
                log("Locking workspace \u2014 unapproved application or website: " + display);
                onLocked.accept(display);

                // 2. Transmit violation to teacher/DB or buffer locally if offline
                try {
                    ViolationStore.record(roomId, studentId, roll, name, display,
                            p.executablePath(), screenshot);
                    log("Flag recorded for " + name + ": " + display
                            + (screenshot != null ? " (with screenshot)" : " (screenshot unavailable)"));
                    onFlagged.accept("Your teacher has been notified that you opened " + display + ".");
                } catch (Exception ex) {
                    log("Could not record flag to remote database (" + ex.getMessage() + ") \u2014 saving to offline queue.");
                    OfflineViolationQueue.enqueue(roomId, studentId, roll, name, display, p.executablePath(), screenshot);
                    onFlagged.accept("Unapproved app flagged offline. Workspace locked.");
                }

                // 3. Register lock in LockStore (handles both online database and local lock state)
                try {
                    LockStore.lock(roomId, name, display);
                    log("Workspace locked \u2014 only the teacher can unlock it.");
                } catch (Exception ex) {
                    log("Could not sync lock to central database (" + ex.getMessage() + ") \u2014 local lock is enforced.");
                }
            } catch (Exception ex) {
                log("Error during violation handling: " + ex.getMessage());
            } finally {
                flagInFlight.set(false);
            }
        }, "violation-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Final check against the freshest list before a flag is written. Uses the
     * REAL pid of the foreground process, so the fresh URL re-check inside can
     * actually read the address bar again (with pid 0 it never could, and the
     * "teacher allowed this page a second ago" safety net never worked).
     */
    private boolean isAllowedAppOrShell(ForegroundMonitor.ActiveProcess p, String detectedApp) {
        String executablePath = p.executablePath();
        String appName = detectedApp == null ? "" : detectedApp.toLowerCase(Locale.ROOT).trim();
        int sepApp = Math.max(appName.lastIndexOf('\\'), appName.lastIndexOf('/'));
        String appFile = sepApp >= 0 ? appName.substring(sepApp + 1) : appName;

        String pathName = executablePath == null ? "" : executablePath.toLowerCase(Locale.ROOT).trim();
        int sepPath = Math.max(pathName.lastIndexOf('\\'), pathName.lastIndexOf('/'));
        String pathFile = sepPath >= 0 ? pathName.substring(sepPath + 1) : pathName;

        // Windows shell UI, system utilities, settings, network, or hardware alerts
        if (isSystemUiOrAlert(appFile, pathName, appName)
                || isSystemUiOrAlert(pathFile, pathName, appName)) {
            return true;
        }

        // When the website allowlist is on and this is the flagged browser again, the
        // fresh URL decides: the teacher may have allowed the page a second ago.
        // Also respect the navigation-forgiveness window: if the student was on an
        // allowed page within NAV_FORGIVENESS_MS the address bar may just be loading.
        if (urlPolicy.enabled() && BrowserProcesses.isBrowser(p.processName())) {
            if (isWindowTitleAllowed(p.windowTitle())) return true;
            String browserKey = p.processName().toLowerCase(Locale.ROOT);
            Long lastAllowed = lastAllowedBrowserMs.get(browserKey);
            boolean withinForgivenessWindow = lastAllowed != null
                    && (System.currentTimeMillis() - lastAllowed) <= NAV_FORGIVENESS_MS;
            if (withinForgivenessWindow) {
                return true;
            }
            ForegroundMonitor.ActiveProcess synthetic = new ForegroundMonitor.ActiveProcess(
                    p.processName(), executablePath, p.pid(), p.windowTitle(), p.windowClass());
            UrlDecision fresh = decideByUrl(synthetic);
            if (fresh.state() == UrlState.ALLOWED || fresh.state() == UrlState.IDLE) {
                return true;
            }
        }

        // The room's allowed process names
        RoomLookup.RoomInfo room = roomInfo;
        if (room.isAllowed(appFile) || room.isAllowed(appName)
                || (!pathFile.isEmpty() && room.isAllowed(pathFile))
                || (!pathName.isEmpty() && room.isAllowed(pathName))) {
            if (!ProcessSecurityValidator.isRenamedImposter(p.processName(), executablePath, null)) {
                return true;
            }
        }

        // The room's allowed apps (name / path / app id)
        for (AllowedApp app : apps) {
            if (app.match(appFile, executablePath, p.windowTitle()).isPresent()
                    || app.match(appName, executablePath, p.windowTitle()).isPresent()) {
                if (!ProcessSecurityValidator.isRenamedImposter(p.processName(), executablePath, app)) {
                    return true;
                }
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
            urlPolicy = RoomUrlPolicyService.loadPolicy(roomId);
            if (changed) {
                log("Allowed list: " + (snapshot.apps().isEmpty() ? "(none)"
                        : String.join(", ", snapshot.apps().stream().map(AllowedApp::describe).toList())));
            }
            if (urlPolicy.enabled()) {
                log("Website allowlist active with " + urlPolicy.rules().size()
                        + (urlPolicy.rules().size() == 1 ? " rule." : " rules."));
            }
        } catch (Exception ignored) {
            // Keep the previous list if the database is briefly busy.
        }
    }

    private void log(String message) {
        System.out.println("[" + LocalTime.now().format(LOG_TIME) + "] [ForegroundCheck] " + message);
    }
}
