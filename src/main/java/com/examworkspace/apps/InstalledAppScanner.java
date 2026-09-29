package com.examworkspace.apps;

import com.examworkspace.model.AppEntry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Discovers applications installed on this Windows PC using the same list the Start menu shows
 * ({@code Get-StartApps}), which covers both classic desktop programs and Microsoft Store apps.
 */
public final class InstalledAppScanner {

    private static final String SCRIPT = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Get-StartApps | ForEach-Object { $_.Name + "`t" + $_.AppID }
            """;

    private InstalledAppScanner() {
    }

    public static List<AppEntry> scan() throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IOException("Detecting installed apps is only supported on Windows. Use \"Browse...\" instead.");
        }

        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();

        Map<String, AppEntry> byId = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String name = line.substring(0, tab).strip();
                String appId = line.substring(tab + 1).strip();
                if (isLaunchableApp(name, appId)) {
                    byId.putIfAbsent(appId.toLowerCase(Locale.ROOT),
                            AppEntry.unsaved(name, appId, AppEntry.TargetType.APP_ID));
                }
            }
        }

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out while listing installed apps.");
        }
        if (process.exitValue() != 0 && byId.isEmpty()) {
            throw new IOException("PowerShell exited with code " + process.exitValue() + " while listing apps.");
        }

        List<AppEntry> apps = new ArrayList<>(byId.values());
        apps.sort(Comparator.comparing(AppEntry::name, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    /** Filters out Start menu entries that are documents, web links or uninstallers rather than apps. */
    private static boolean isLaunchableApp(String name, String appId) {
        if (name.isEmpty() || appId.isEmpty()) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerId = appId.toLowerCase(Locale.ROOT);
        if (lowerName.startsWith("uninstall") || lowerId.startsWith("http:") || lowerId.startsWith("https:")) {
            return false;
        }
        int sep = Math.max(lowerId.lastIndexOf('\\'), lowerId.lastIndexOf('/'));
        if (sep >= 0) {
            String fileName = lowerId.substring(sep + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                String ext = fileName.substring(dot + 1);
                return ext.equals("exe") || ext.equals("msc") || ext.equals("cpl");
            }
        }
        return true;
    }
}
