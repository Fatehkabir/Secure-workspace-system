package com.examworkspace.apps;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Resolves Windows Store / AppUserModelID app IDs to the .exe process name
 * that appears in the foreground when the app is running — which is what the
 * student monitor detects.
 *
 * <p>Uses PowerShell's {@code Get-AppxPackage} + {@code Get-AppxPackageManifest}
 * to look up the app's entry-point executable. Falls back gracefully if
 * PowerShell is unavailable or the package isn't found.
 *
 * <p>Results are cached in-process so repeated calls for the same ID are free.
 */
public final class AppIdResolver {

    private static final Map<String, String> CACHE = new HashMap<>();

    private AppIdResolver() {
    }

    /**
     * Resolves one or more AppUserModelIDs to their foreground exe names.
     *
     * <p>The script takes a list of AppIDs (space-separated, single-quoted), looks
     * up each package's primary executable from its manifest Application element,
     * and prints {@code appId\texeName.exe} lines.
     *
     * @param appIds the AppUserModelIDs to resolve (e.g. {@code Microsoft.WindowsCalculator_…!App})
     * @return a map from lower-cased AppID → lower-cased exe filename; missing entries mean
     *         the package was not found or resolution failed.
     */
    public static Map<String, String> resolve(Iterable<String> appIds) {
        // Collect IDs not already cached.
        Map<String, String> result = new HashMap<>();
        java.util.List<String> toResolve = new java.util.ArrayList<>();
        for (String id : appIds) {
            String key = id.toLowerCase(Locale.ROOT);
            if (CACHE.containsKey(key)) {
                result.put(key, CACHE.get(key));
            } else {
                toResolve.add(id);
            }
        }
        if (toResolve.isEmpty()) {
            return result;
        }

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return result; // non-Windows: no resolution possible
        }

        // Build a PowerShell snippet that iterates the provided AppIDs.
        // For each one, find the package and extract its Application Executable attribute.
        StringBuilder sb = new StringBuilder();
        sb.append("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n");
        for (String id : toResolve) {
            // The AppUserModelID format is "PackageFamilyName!AppId" or just a path.
            // We extract the package family name (everything before '!').
            String escaped = id.replace("'", "''");
            sb.append("$appId = '").append(escaped).append("'\n");
            sb.append("$bang = $appId.IndexOf('!')\n");
            sb.append("if ($bang -gt 0) {\n");
            sb.append("  $family = $appId.Substring(0, $bang)\n");
            sb.append("  # Strip the unique suffix after the last underscore to get the family prefix\n");
            sb.append("  $pkg = Get-AppxPackage | Where-Object { $_.PackageFamilyName -eq $family } | Select-Object -First 1\n");
            sb.append("  if ($pkg) {\n");
            sb.append("    try {\n");
            sb.append("      $man = Get-AppxPackageManifest -Package $pkg.PackageFullName\n");
            sb.append("      $exe = $man.Package.Applications.Application | Select-Object -ExpandProperty Executable -First 1\n");
            sb.append("      if ($exe) {\n");
            sb.append("        $leaf = [System.IO.Path]::GetFileName($exe)\n");
            sb.append("        Write-Output ($appId + \"`t\" + $leaf)\n");
            sb.append("      }\n");
            sb.append("    } catch {}\n");
            sb.append("  }\n");
            sb.append("}\n");
        }

        String encoded = Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_16LE));
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        String id = line.substring(0, tab).strip().toLowerCase(Locale.ROOT);
                        String exe = line.substring(tab + 1).strip().toLowerCase(Locale.ROOT);
                        if (!exe.isEmpty()) {
                            CACHE.put(id, exe);
                            result.put(id, exe);
                        }
                    }
                }
            }
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort: if PowerShell fails, return what we have
        }
        return result;
    }
}
