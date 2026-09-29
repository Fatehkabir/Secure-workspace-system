package com.examworkspace.ui;

import com.examworkspace.db.Database;
import com.examworkspace.model.AppEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.image.Image;

/** Extracts each installed app's icon once and reuses the PNG afterwards. */
public final class AppIconCache {

    private static final AppIconCache SHARED = new AppIconCache();

    private final ConcurrentHashMap<String, Image> images = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> unavailable = ConcurrentHashMap.newKeySet();
    private final List<AppEntry> queue = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final ExecutorService workers = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "app-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "app-icons-debounce");
        thread.setDaemon(true);
        return thread;
    });
    private boolean flushScheduled;

    private AppIconCache() {
    }

    public static AppIconCache shared() {
        return SHARED;
    }

    /** Already-extracted icon, or null while it is still being looked up. */
    public Image get(String launchTarget) {
        Image cached = images.get(launchTarget);
        if (cached != null) {
            return cached;
        }
        Path file = fileFor(launchTarget);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) == 0) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        Image loaded = new Image(file.toUri().toString(), 96, 96, true, true, false);
        images.put(launchTarget, loaded);
        return loaded;
    }

    /** Called on the JavaFX thread after a batch of icons has been extracted. */
    public void subscribe(Runnable onReady) {
        synchronized (queue) {
            listeners.add(onReady);
        }
    }

    /** Queues an extraction. Subscribers are notified when the batch finishes. */
    public void request(AppEntry app) {
        if (app == null || unavailable.contains(app.launchTarget()) || saved(app.launchTarget())
                || images.containsKey(app.launchTarget())) {
            return;
        }
        synchronized (queue) {
            boolean queued = queue.stream().anyMatch(existing -> existing.launchTarget().equals(app.launchTarget()));
            if (!queued) {
                queue.add(app);
            }
            if (!flushScheduled) {
                flushScheduled = true;
                debounce.schedule(this::submitFlush, 250, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void submitFlush() {
        List<AppEntry> batch;
        synchronized (queue) {
            flushScheduled = false;
            batch = List.copyOf(queue);
            queue.clear();
        }
        if (!batch.isEmpty()) {
            workers.submit(() -> extract(batch));
        }
    }

    private void extract(List<AppEntry> batch) {
        try {
            Files.createDirectories(iconDir());
            Path script = iconDir().resolve("extract-icons.ps1");
            Files.writeString(script, SCRIPT);
            Path list = Files.createTempFile("exam-icons-", ".txt");
            List<String> lines = new ArrayList<>();
            for (AppEntry app : batch) {
                if (saved(app.launchTarget()) || unavailable.contains(app.launchTarget())) {
                    continue;
                }
                lines.add(fileFor(app.launchTarget()) + "\t" + app.launchTarget() + "\t" + app.name());
            }
            if (lines.isEmpty()) {
                Files.deleteIfExists(list);
                notifyListeners();
                return;
            }
            Files.write(list, lines, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ListFile", list.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor(90, TimeUnit.SECONDS);
            Files.deleteIfExists(list);
        } catch (Exception ignored) {
            // Icons are decorative. The initials fallback stays in place.
        }
        for (AppEntry app : batch) {
            if (!saved(app.launchTarget()) && app.targetType() == AppEntry.TargetType.EXECUTABLE) {
                unavailable.add(app.launchTarget());
            }
        }
        notifyListeners();
        synchronized (queue) {
            if (!queue.isEmpty() && !flushScheduled) {
                flushScheduled = true;
                debounce.schedule(this::submitFlush, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void notifyListeners() {
        List<Runnable> ready;
        synchronized (queue) {
            ready = List.copyOf(listeners);
        }
        if (ready.isEmpty()) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            ready.forEach(Runnable::run);
        } else {
            Platform.runLater(() -> ready.forEach(Runnable::run));
        }
    }

    private static boolean saved(String launchTarget) {
        Path file = fileFor(launchTarget);
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path iconDir() {
        return Database.dataDir().resolve("icons");
    }

    private static Path fileFor(String launchTarget) {
        return iconDir().resolve(sha256(launchTarget) + ".png");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SCRIPT = """
            param([Parameter(Mandatory = $true)][string]$ListFile)
            Add-Type -AssemblyName System.Drawing
            $known = @{
                '{6D809377-6AF0-444B-8957-A3773F02200E}' = $env:ProgramFiles
                '{7C5A40EF-A0FB-4BFC-874A-C0F2E0B9FA8E}' = ${env:ProgramFiles(x86)}
                '{F38BF404-1D43-42F2-9305-67DE0B28FC23}' = $env:WINDIR
                '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}' = (Join-Path $env:WINDIR 'System32')
                '{D65231B0-B2F1-4857-A4CE-A8E7C6EA7D27}' = (Join-Path $env:WINDIR 'SysWOW64')
            }
            $packages = $null

            function Resolve-Exe([string]$target) {
                if (Test-Path -LiteralPath $target) { return (Resolve-Path -LiteralPath $target).Path }
                if ($target -match '^\\{([0-9A-Fa-f-]+)\\}(.*)$') {
                    $guid = '{' + $Matches[1].ToUpperInvariant() + '}'
                    $rest = $Matches[2].TrimStart('\\')
                    if ($known.ContainsKey($guid)) {
                        $full = Join-Path $known[$guid] $rest
                        if (Test-Path -LiteralPath $full) { return $full }
                    }
                }
                return $null
            }

            $shellOk = $false
            try {
                Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
            using System;
            using System.Drawing;
            using System.Drawing.Imaging;
            using System.Runtime.InteropServices;
            public class ShellPng {
                [StructLayout(LayoutKind.Sequential)]
                public struct SIZE { public int cx; public int cy; }
                [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                interface IShellItemImageFactory {
                    [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr phbm);
                }
                [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
                static extern int SHCreateItemFromParsingName(string pszPath, IntPtr pbc, ref Guid riid,
                    [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory factory);
                [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr hObject);
                public static bool Save(string parsingName, string outFile) {
                    Guid iid = new Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b");
                    IShellItemImageFactory factory;
                    if (SHCreateItemFromParsingName(parsingName, IntPtr.Zero, ref iid, out factory) != 0 || factory == null) return false;
                    SIZE size = new SIZE { cx = 64, cy = 64 };
                    IntPtr hbmp;
                    if (factory.GetImage(size, 0, out hbmp) != 0 || hbmp == IntPtr.Zero) return false;
                    try {
                        using (Bitmap bmp = Bitmap.FromHbitmap(hbmp)) bmp.Save(outFile, ImageFormat.Png);
                        return true;
                    } finally { DeleteObject(hbmp); }
                }
            }
            '@
                $shellOk = $true
            } catch { }

            function Save-Exe([string]$path, [string]$out) {
                $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($path)
                if ($null -eq $icon) { return }
                $bmp = $icon.ToBitmap()
                $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
                $bmp.Dispose()
                $icon.Dispose()
            }

            function Save-Icon([string]$parsingName, [string]$out) {
                if ([string]::IsNullOrWhiteSpace($parsingName)) { return }
                [void][ShellPng]::Save($parsingName, $out)
            }

            function Find-Shortcut([string]$name) {
                $roots = @(
                    (Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs'),
                    (Join-Path $env:ProgramData 'Microsoft\\Windows\\Start Menu\\Programs')
                )
                foreach ($root in $roots) {
                    if (-not (Test-Path -LiteralPath $root)) { continue }
                    $hit = Get-ChildItem -LiteralPath $root -Recurse -Filter *.lnk -ErrorAction SilentlyContinue |
                        Where-Object { $_.BaseName -like "*$name*" } |
                        Select-Object -First 1
                    if ($null -ne $hit) { return $hit.FullName }
                }
                return $null
            }

            Get-Content -LiteralPath $ListFile -Encoding UTF8 | ForEach-Object {
                $parts = $_.Split("`t")
                if ($parts.Count -lt 2 -or (Test-Path -LiteralPath $parts[0])) { return }
                $out = $parts[0]
                $target = $parts[1]
                $name = if ($parts.Count -gt 2) { $parts[2] } else { '' }
                try {
                    if ($target.Contains('!')) {
                        if ($shellOk) { Save-Icon ("shell:AppsFolder\\" + $target) $out }
                    } else {
                        $exe = Resolve-Exe $target
                        if ($null -eq $exe -and $name) {
                            $lnk = Find-Shortcut $name
                            if ($null -ne $lnk) {
                                if ($shellOk) { Save-Icon $lnk $out }
                                if (-not (Test-Path -LiteralPath $out)) {
                                    $w = New-Object -ComObject WScript.Shell
                                    $exe = $w.CreateShortcut($lnk).TargetPath
                                }
                            }
                        }
                        if ($null -ne $exe) {
                            if ($shellOk) { Save-Icon $exe $out }
                            if (-not (Test-Path -LiteralPath $out)) { Save-Exe $exe $out }
                        }
                    }
                    if (-not (Test-Path -LiteralPath $out) -and $name) {
                        $lnk = Find-Shortcut $name
                        if ($null -ne $lnk -and $shellOk) { Save-Icon $lnk $out }
                        if ($null -ne $lnk -and -not (Test-Path -LiteralPath $out)) { Save-Icon $lnk $out }
                    }
                } catch { }
            }
            """;
}
