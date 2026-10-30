package com.examworkspace.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * One app the teacher allowed in a room, plus everything we know about it,
 * so the student client can recognise it when it is in the foreground.
 *
 * <p>The old approach compared only a saved ".exe name", which was empty or wrong
 * for most apps added from the installed-apps list. This matches on several signals:
 * <ol>
 *   <li>the saved process name,</li>
 *   <li>the executable path / file name,</li>
 *   <li>a ".exe" inside the Start-menu app id,</li>
 *   <li>the app's display name vs the running exe name (Chrome, VS Code, ...),</li>
 *   <li>the window title, for Microsoft Store apps hosted by ApplicationFrameHost.exe.</li>
 * </ol>
 */
public record AllowedApp(String name, String launchTarget, String targetType, String processName) {

    private static final String SEP = "~~";
    private static final String UWP_HOST = "applicationframehost.exe";
    private static final Set<String> NO_FUZZY = Set.of(
            "applicationframehost", "svchost", "dllhost", "runtimebroker", "conhost", "java", "javaw");

    // ── transport (server -> student) ────────────────────────────────────────

    public String encode() {
        return nz(name) + SEP + nz(launchTarget) + SEP + nz(targetType) + SEP + nz(processName);
    }

    public static Optional<AllowedApp> decode(String text) {
        String[] parts = text.split(java.util.regex.Pattern.quote(SEP), -1);
        if (parts.length < 4) return Optional.empty();
        return Optional.of(new AllowedApp(parts[0], parts[1], parts[2], parts[3]));
    }

    /** Reads the "apps":[ "...", "..." ] array out of a server response. */
    public static List<AllowedApp> parseFromJson(String json) {
        List<AllowedApp> out = new ArrayList<>();
        if (json == null) return out;
        int key = json.indexOf("\"apps\"");
        if (key < 0) return out;
        int i = json.indexOf('[', key);
        if (i < 0) return out;
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ']') break;
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < json.length()) {
                    char d = json.charAt(i);
                    if (d == '\\' && i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == '"') break;
                    sb.append(d);
                    i++;
                }
                i++; // closing quote
                decode(sb.toString()).ifPresent(out::add);
                continue;
            }
            i++;
        }
        return out;
    }

    // ── display ──────────────────────────────────────────────────────────────

    public String describe() {
        String proc = processName != null && !processName.isBlank() ? processName : fileName(lower(launchTarget));
        return proc.endsWith(".exe") ? name + " (" + proc + ")" : name;
    }

    // ── matching ─────────────────────────────────────────────────────────────

    /** @return why the foreground process matches this allowed app, or empty if it does not. */
    public Optional<String> match(String exeName, String exePath, String windowTitle) {
        String exe = lower(exeName);
        String path = lower(exePath);
        if (exe.isEmpty()) return Optional.empty();

        String proc = lower(processName);
        if (!proc.isEmpty() && proc.equals(exe)) return Optional.of("process name " + exe);

        String target = lower(launchTarget);
        if (!target.isEmpty()) {
            String targetFile = fileName(target);
            if ("EXECUTABLE".equals(targetType)) {
                if (target.equals(path) || targetFile.equals(exe)) return Optional.of("executable " + targetFile);
            } else if (targetFile.endsWith(".exe") && targetFile.equals(exe)) {
                return Optional.of("app path " + targetFile);
            }
        }

        // Microsoft Store apps (Calculator, Settings, ...) are hosted by ApplicationFrameHost.exe;
        // the only reliable clue is the window title.
        if (exe.equals(UWP_HOST)) {
            String title = compact(windowTitle);
            String n = compact(name);
            if (n.length() >= 3 && title.length() >= 3 && (title.contains(n) || n.contains(title))) {
                return Optional.of("window title \"" + windowTitle + "\"");
            }
            return Optional.empty();
        }

        // The exact exe is already known (saved process name or a chosen .exe file), so an
        // exe that did not match above is a different program: no guessing by name.
        if ("EXECUTABLE".equals(targetType) || proc.endsWith(".exe")) return Optional.empty();

        String base = compact(stripExe(exe));
        if (NO_FUZZY.contains(base)) return Optional.empty();
        String baseNoDigits = base.replaceAll("\\d+$", "");
        if (baseNoDigits.length() >= 3) base = baseNoDigits;
        if (base.length() < 3) return Optional.empty();

        String n = compact(name);
        if (n.length() >= 3 && n.contains(base)) return Optional.of("app name \"" + name + "\"");
        if (n.length() >= 5 && base.contains(n)) return Optional.of("app name \"" + name + "\"");
        if (base.length() >= 5 && compact(target).contains(base)) return Optional.of("app id " + launchTarget);
        return Optional.empty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String nz(String s) { return s == null ? "" : s; }

    private static String lower(String s) { return s == null ? "" : s.strip().toLowerCase(Locale.ROOT); }

    private static String compact(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String fileName(String path) {
        int sep = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    private static String stripExe(String exe) {
        return exe.endsWith(".exe") ? exe.substring(0, exe.length() - 4) : exe;
    }
}
