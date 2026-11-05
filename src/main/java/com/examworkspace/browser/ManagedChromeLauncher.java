package com.examworkspace.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ManagedChromeLauncher {

    public record ChromeInstance(Process process, int debuggingPort, Path userDataDir) {
    }

    private ManagedChromeLauncher() {
    }

    public static ChromeInstance launchDedicatedSession(Path userDataDir) throws IOException {
        Path resolvedDir = userDataDir == null
                ? Files.createTempDirectory("examworkspace-chrome-")
                : userDataDir;
        Files.createDirectories(resolvedDir);

        int port = findFreePort();
        List<String> command = new ArrayList<>();
        command.add(resolveChromeExecutable());
        command.add("--user-data-dir=" + resolvedDir.toAbsolutePath());
        command.add("--remote-debugging-port=" + port);
        command.add("--no-first-run");
        command.add("--disable-background-networking");
        command.add("--disable-extensions");
        command.add("--new-window");
        command.add("about:blank");

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        return new ChromeInstance(process, port, resolvedDir);
    }

    public static String readDevToolsActivePort(Path userDataDir) {
        if (userDataDir == null) {
            return null;
        }
        Path portFile = userDataDir.resolve("DevToolsActivePort");
        if (!Files.exists(portFile)) {
            return null;
        }
        try {
            return Files.readString(portFile).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    public static int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static String resolveChromeExecutable() {
        List<String> candidates = List.of(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "google-chrome",
                "chromium",
                "chrome",
                "msedge"
        );
        for (String candidate : candidates) {
            if (candidate.contains("\\") || candidate.contains("/")) {
                var path = Path.of(candidate);
                if (Files.exists(path)) {
                    return candidate;
                }
            } else {
                String path = System.getenv("PATH");
                if (path != null && path.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return "chrome";
    }
}






































































