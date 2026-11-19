package com.examworkspace.apps;

import com.examworkspace.model.AppEntry;
import java.io.File;
import java.io.IOException;

public final class AppLauncher {

    private AppLauncher() {
    }

    /**
     * Resolves an executable on the current device. If the exact saved path
     * exists, it is used. Otherwise, standard system paths and PATH environment
     * variable are probed for the executable name.
     */
    public static File resolveExecutable(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        File direct = new File(target);
        if (direct.isFile() && direct.canExecute()) {
            return direct;
        }

        String fileName = direct.getName();
        if (fileName.isBlank()) {
            return null;
        }

        // 1. Probe PATH environment variable
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) continue;
                File candidate = new File(dir.trim(), fileName);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate;
                }
            }
        }

        // 2. Probe standard Windows installation locations
        String progFiles = System.getenv("ProgramFiles");
        String progFilesX86 = System.getenv("ProgramFiles(x86)");
        String localApp = System.getenv("LocalAppData");
        String sysRoot = System.getenv("SystemRoot");

        String[] standardDirs = {
                progFiles,
                progFilesX86,
                localApp != null ? localApp + "\\Programs" : null,
                sysRoot != null ? sysRoot + "\\System32" : null,
                sysRoot
        };
        for (String base : standardDirs) {
            if (base == null || base.isBlank()) continue;
            File candidate = new File(base, fileName);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Checks whether an application is installed and available to launch on this device.
     */
    public static boolean isAvailable(AppEntry app) {
        if (app == null) return false;
        return switch (app.targetType()) {
            case APP_ID -> true; // Windows Start menu apps handled via shell:AppsFolder
            case EXECUTABLE -> resolveExecutable(app.launchTarget()) != null;
        };
    }

    public static void launch(AppEntry app) throws IOException {
        ProcessBuilder builder = switch (app.targetType()) {
            case APP_ID -> new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + app.launchTarget());
            case EXECUTABLE -> {
                File exe = resolveExecutable(app.launchTarget());
                if (exe == null || !exe.isFile()) {
                    throw new IOException("“" + app.name() + "” is allowed for this room, but could not be found on this computer.");
                }
                yield new ProcessBuilder(exe.getAbsolutePath()).directory(exe.getParentFile());
            }
        };
        builder.start();
    }
}
