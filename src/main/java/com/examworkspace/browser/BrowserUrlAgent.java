package com.examworkspace.browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the REAL address-bar URL of whichever browser window is in the
 * foreground, so the room's website allowlist can be enforced against the page
 * the student is actually viewing (Chrome, Edge, Brave, Opera, Vivaldi,
 * Firefox, ...).
 *
 * <p>How it works: browsers draw their own UI, so the URL is not in the window
 * title and not a Win32 control. Instead one small hidden PowerShell helper
 * (built into Windows 10/11) is started, which each second:
 * <ol>
 *   <li>gets the foreground window and its process,</li>
 *   <li>if it is a known browser, asks UI Automation for the URL-looking text
 *       field near the top of that window (the address bar),</li>
 *   <li>prints one line {@code pid|process|url} back to this class.</li>
 * </ol>
 *
 * <p>This class keeps only the most recent line. The monitor asks for the URL
 * of the foreground process and gets it if the answer is younger than a couple
 * of seconds; otherwise the URL is treated as "unreadable" and the monitor
 * deliberately does NOT flag anything (no false violations when a browser
 * refuses to cooperate).
 *
 * <p>Windows-only. Everywhere else it simply never produces a URL and the
 * check degrades gracefully.
 */
public final class BrowserUrlAgent {

    /** One foreground reading taken by the helper. */
    public record Snapshot(long pid, String processName, String url, long whenMs) {

        public static final Snapshot EMPTY = new Snapshot(0, "", "", 0L);
    }

    private static final long FRESH_MS = 3000;
    private static final long RESTART_DELAY_MS = 5000;

    private static BrowserUrlAgent instance;

    /** process|url reading shared between the reader thread and the monitor thread. */
    private volatile Snapshot latest = Snapshot.EMPTY;
    private volatile Process process;
    private volatile boolean started;
    private volatile boolean stopped;
    private String lastError = "";
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public static synchronized BrowserUrlAgent get() {
        if (instance == null) {
            instance = new BrowserUrlAgent();
        }
        return instance;
    }

    private BrowserUrlAgent() {
    }

    /** Starts the hidden helper. Safe to call more than once; harmless off Windows. */
    public synchronized void start() {
        if (started || stopped) {
            return;
        }
        started = true;
        spawn();
    }

    public synchronized void stop() {
        stopped = true;
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
            process = null;
        }
    }

    /**
     * @param pid        the foreground process id
     * @param processName the foreground executable name (extra safety check)
     * @return the address-bar URL, when a fresh reading for this exact process exists
     */
    public Optional<String> currentUrl(long pid, String processName) {
        Snapshot s = latest;
        if (s.pid() <= 0 || s.pid() != pid) {
            return Optional.empty();
        }
        if (processName != null && !processName.isBlank()
                && !sameProcess(s.processName(), processName)) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - s.whenMs() > FRESH_MS) {
            return Optional.empty();
        }
        String url = s.url();
        return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    /**
     * Compares two process-name spellings regardless of the ".exe" suffix, path
     * or case: "chrome" and "chrome.exe" are the same process. This exact
     * mismatch (the monitor reports "chrome.exe", the helper reports "chrome")
     * used to make every URL lookup fail, which silently disabled the whole
     * website-allowlist enforcement -- students saw a warning but were never
     * flagged or locked.
     */
    static boolean sameProcess(String a, String b) {
        return baseProcessName(a).equals(baseProcessName(b));
    }

    /** "C:\...\Chrome.EXE" / "CHROME.EXE" / "chrome" all become "chrome". */
    static String baseProcessName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim().toLowerCase(Locale.ROOT);
        int sep = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        if (sep >= 0) {
            value = value.substring(sep + 1);
        }
        if (value.endsWith(".exe")) {
            value = value.substring(0, value.length() - 4);
        }
        return value;
    }

    /** True when the helper is alive and has produced at least one reading. */
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive() && latest.whenMs() > 0;
    }

    public String stateForLog() {
        Snapshot s = latest;
        return "agent alive=" + isAlive() + ", last reading " +
                (s.whenMs() == 0 ? "(none)" : ((System.currentTimeMillis() - s.whenMs()) + "ms ago, pid " + s.pid()))
                + (lastError.isEmpty() ? "" : ", last error: " + lastError);
    }

    // ── helper process management ────────────────────────────────────────────

    private void spawn() {
        final Path script;
        try {
            script = writeHelperScript();
        } catch (IOException e) {
            lastError = e.getMessage() == null ? "cannot write helper script" : e.getMessage();
            latest = Snapshot.EMPTY;
            scheduleRestartIfNeeded();
            return;
        }
        Thread starter = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden",
                        "-File", script.toAbsolutePath().toString());
                pb.redirectErrorStream(false);
                Process p = pb.start();
                process = p;
                Thread reader = new Thread(() -> readLoop(p, script), "browser-url-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                lastError = e.getMessage() == null ? "cannot start helper" : e.getMessage();
                latest = Snapshot.EMPTY;
            }
        }, "browser-url-spawner");
        starter.setDaemon(true);
        starter.start();
    }

    private void readLoop(Process p, Path script) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Snapshot parsed = parseLine(line);
                if (parsed != null) {
                    latest = parsed;
                    lastError = "";
                }
            }
        } catch (IOException ignored) {
            // stream closed when the process dies
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                }
            }
        }
        scheduleRestartIfNeeded();
    }

    /** Accepts {@code pid|process|url}; anything else is ignored. */
    static Snapshot parseLine(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("\\|", 3);
        if (parts.length < 2) {
            return null;
        }
        long pid;
        try {
            pid = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String proc = parts.length > 1 ? parts[1].trim() : "";
        String url = parts.length > 2 ? parts[2].trim() : "";
        if (url.length() > 2048) {
            url = url.substring(0, 2048);
        }
        return new Snapshot(pid, proc.toLowerCase(Locale.ROOT), url, System.currentTimeMillis());
    }

    private void scheduleRestartIfNeeded() {
        if (stopped) {
            return;
        }
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        Thread restarter = new Thread(() -> {
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            restarting.set(false);
            if (!stopped) {
                synchronized (this) {
                    if (!stopped) {
                        spawn();
                    }
                }
            }
        }, "browser-url-restarter");
        restarter.setDaemon(true);
        restarter.start();
    }

    // ── the embedded PowerShell helper ───────────────────────────────────────

    /**
     * Writes the helper script to a temp file (PowerShell needs a real -File path).
     * The script loops once a second until the file is deleted with the process:
     * foreground window &rarr; process id/name &rarr; address-bar text via UIA.
     */
    private static Path writeHelperScript() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "exam-workspace-url-agent");
        Files.createDirectories(dir);
        Path script = dir.resolve("url-agent-" + ProcessHandle.current().pid() + ".ps1");
        Files.writeString(script, HELPER_SCRIPT, StandardCharsets.UTF_8);
        script.toFile().deleteOnExit();
        return script;
    }

    private static final String HELPER_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $ProgressPreference = 'SilentlyContinue'
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
            Add-Type -AssemblyName UIAutomationClient | Out-Null
            Add-Type -AssemblyName UIAutomationTypes | Out-Null
            Add-Type @'
            using System;
            using System.Runtime.InteropServices;
            public static class FgWin {
                [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
            }
            '@

            $browsers = @('chrome','chromium','msedge','edge','brave','firefox','vivaldi','opera','opera_gx',
                          'whale','yandex','librewolf','waterfox','thorium','iridium','slimjet','epic','tor')
            $editCond = New-Object System.Windows.Automation.PropertyCondition(
                [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
                [System.Windows.Automation.ControlType]::Edit)

            function Get-ForegroundUrl {
                param([IntPtr]$Handle, [uint32]$ProcId)
                $element = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
                if ($element -eq $null) { return '' }
                try {
                    $winRect = $element.Current.BoundingRectangle
                } catch { $winRect = [System.Windows.Rect]::Empty }
                $edits = $element.FindAll([System.Windows.Automation.TreeScope]::Descendants, $editCond)
                if ($edits -eq $null) { return '' }
                $best = ''
                $bestScore = 0
                foreach ($e in $edits) {
                    try {
                        $value = $null
                        try {
                            $vp = $e.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                            if ($vp -ne $null) { $value = $vp.Current.Value }
                        } catch {}
                        if ([string]::IsNullOrWhiteSpace($value)) { continue }
                        if ($value.Length -gt 2048) { continue }
                        $v = $value.Trim()
                        $score = 0

                        $cls = ''
                        try { $cls = $e.Current.ClassName } catch {}
                        $name = ''
                        try { $name = $e.Current.Name } catch {}
                        $autoId = ''
                        try { $autoId = $e.Current.AutomationId } catch {}

                        # Identify browser omnibox / address bar
                        if ($cls -match 'Omnibox|urlbar' -or $autoId -match 'urlbar|address' -or $name -match 'address and search|search or enter') {
                            $score = $score + 10
                        }

                        if ($v -match '^[a-z][a-z0-9+.-]*://') { $score = $score + 4 }
                        if ($v.StartsWith('www.')) { $score = $score + 2 }
                        if ($v.Contains('.') -and -not $v.Contains(' ')) { $score = $score + 2 }
                        try {
                            $r = $e.Current.BoundingRectangle
                            if ($winRect -ne $null -and $r -ne $null -and -not $r.IsEmpty -and -not $winRect.IsEmpty) {
                                $relTop = $r.Top - $winRect.Top
                                if ($relTop -ge 0 -and $relTop -lt 160) { $score = $score + 3 }
                            }
                        } catch {}
                        if ($score -gt $bestScore) {
                            $bestScore = $score
                            $best = $v
                        }
                    } catch { continue }
                }
                if ($bestScore -lt 2) { return '' }
                return $best
            }

            while ($true) {
                try {
                    $handle = [FgWin]::GetForegroundWindow()
                    $procId = [uint32]0
                    if ($handle -ne [IntPtr]::Zero) {
                        [void][FgWin]::GetWindowThreadProcessId($handle, [ref]$procId)
                    }
                    $procName = ''
                    $url = ''
                    if ($procId -gt 0) {
                        try {
                            $p = Get-Process -Id $procId -ErrorAction Stop
                            $procName = $p.ProcessName.ToLowerInvariant()
                        } catch { $procName = '' }
                        if ($browsers -contains $procName) {
                            $url = Get-ForegroundUrl -Handle $handle -ProcId $procId
                        }
                    }
                    [Console]::Out.WriteLine(('{0}|{1}|{2}' -f $procId, $procName, $url))
                    [Console]::Out.Flush()
                } catch {}
                Start-Sleep -Milliseconds 900
            }
            """;
}
