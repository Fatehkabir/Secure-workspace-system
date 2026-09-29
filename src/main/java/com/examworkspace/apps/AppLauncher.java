package com.examworkspace.apps;

import com.examworkspace.model.AppEntry;
import java.io.File;
import java.io.IOException;

public final class AppLauncher {

    private AppLauncher() {
    }

    public static void launch(AppEntry app) throws IOException {
        ProcessBuilder builder = switch (app.targetType()) {
            case APP_ID -> new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + app.launchTarget());
            case EXECUTABLE -> {
                File exe = new File(app.launchTarget());
                if (!exe.isFile()) {
                    throw new IOException("File not found: " + exe);
                }
                yield new ProcessBuilder(exe.getAbsolutePath()).directory(exe.getParentFile());
            }
        };
        builder.start();
    }
}
