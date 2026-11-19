package com.examworkspace.monitor;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Version;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates executable integrity and detects attempts by students to bypass
 * room restrictions by renaming an unallowed app (e.g. cheat.exe or discord.exe)
 * to an allowed app's name (e.g. notepad.exe or python.exe).
 *
 * <p>Uses multi-layer defense:
 * <ol>
 *   <li>System-binary path integrity: authentic system tools (notepad, calc, cmd, etc.)
 *       must reside in System32 / Windows. Any executable running from user folders
 *       (Downloads, Desktop, Temp) with a system tool name is an imposter.</li>
 *   <li>PE Version Information inspection: queries Win32 VERSIONINFO resources to
 *       verify authentic product details vs known prohibited tools.</li>
 *   <li>Prohibited app fingerprinting: detects known cheating/communication tools
 *       (Discord, Telegram, Cheat Engine, etc.) even if their .exe has been renamed.</li>
 * </ol>
 */
public final class ProcessSecurityValidator {

    public record BinaryMetadata(String originalFilename, String fileDescription, String productName, String companyName) {}

    private static final Map<String, BinaryMetadata> META_CACHE = new ConcurrentHashMap<>();

    /** Known Windows system and shell tools that must strictly reside in system directories. */
    private static final Set<String> SYSTEM_TOOLS = Set.of(
            "notepad.exe", "calc.exe", "calculator.exe", "cmd.exe", "powershell.exe",
            "mspaint.exe", "write.exe", "regedit.exe", "taskmgr.exe", "explorer.exe",
            "snippingtool.exe", "magnify.exe", "osk.exe"
    );

    /** Untrusted user directory fragments where legitimate system apps NEVER run from. */
    private static final Set<String> SUSPICIOUS_DIRECTORIES = Set.of(
            "\\downloads\\", "/downloads/",
            "\\desktop\\", "/desktop/",
            "\\appdata\\local\\temp\\", "/appdata/local/temp/",
            "\\temp\\", "/temp/",
            "\\tmp\\", "/tmp/"
    );

    /**
     * Common generic framework runtime/wrapper binary names.
     * Legitimate applications often have these as their OriginalFilename
     * (e.g. VS Code is built with Electron and has electron.exe as OriginalFilename;
     * Java uses shimconsole.exe). They should NOT be treated as imposter indicators.
     */
    private static final Set<String> GENERIC_RUNTIMES = Set.of(
            "electron.exe", "electron",
            "shimconsole.exe", "app.exe", "main.exe", "launcher.exe",
            "javaw.exe", "java.exe", "python.exe", "python3.exe", "pythonw.exe",
            "nw.exe", "cefclient.exe", "qt5.dll", "openwith.exe", "conhost.exe"
    );

    /**
     * Well-known communication, cheating, gaming, or media tools that students might
     * attempt to disguise during an exam.
     */
    private static final Set<String> PROHIBITED_SIGNATURES = Set.of(
            "discord", "telegram", "whatsapp", "slack", "cheatengine", "cheat engine",
            "spotify", "steam", "epicgames", "epic games", "battle.net", "utorrent",
            "bittorrent", "obs studio", "obs64", "wechat", "viber", "skype"
    );

    private ProcessSecurityValidator() {}

    /**
     * Reads embedded PE version resources (OriginalFilename, FileDescription, etc.)
     * from a Windows binary using Version.dll. Results are cached per file path.
     */
    public static BinaryMetadata readMetadata(String executablePath) {
        if (executablePath == null || executablePath.isBlank()) {
            return null;
        }
        String normalizedPath = executablePath.trim().toLowerCase(Locale.ROOT);
        return META_CACHE.computeIfAbsent(normalizedPath, path -> {
            try {
                IntByReference dwHandle = new IntByReference();
                int size = Version.INSTANCE.GetFileVersionInfoSize(executablePath, dwHandle);
                if (size <= 0) return null;

                Memory buffer = new Memory(size);
                if (!Version.INSTANCE.GetFileVersionInfo(executablePath, 0, size, buffer)) {
                    return null;
                }

                PointerByReference transBuf = new PointerByReference();
                IntByReference transLen = new IntByReference();
                if (!Version.INSTANCE.VerQueryValue(buffer, "\\VarFileInfo\\Translation", transBuf, transLen)) {
                    return null;
                }

                Pointer ptr = transBuf.getValue();
                short lang = ptr.getShort(0);
                short codepage = ptr.getShort(2);
                String subBlockPrefix = String.format("\\StringFileInfo\\%04x%04x\\", lang, codepage);

                String orig = queryBlock(buffer, subBlockPrefix + "OriginalFilename");
                String desc = queryBlock(buffer, subBlockPrefix + "FileDescription");
                String prod = queryBlock(buffer, subBlockPrefix + "ProductName");
                String comp = queryBlock(buffer, subBlockPrefix + "CompanyName");

                // If primary translation didn't yield strings, try standard US English codepage
                if (orig == null && desc == null && prod == null) {
                    String usBlock = "\\StringFileInfo\\040904b0\\";
                    orig = queryBlock(buffer, usBlock + "OriginalFilename");
                    desc = queryBlock(buffer, usBlock + "FileDescription");
                    prod = queryBlock(buffer, usBlock + "ProductName");
                    comp = queryBlock(buffer, usBlock + "CompanyName");
                }

                return new BinaryMetadata(orig, desc, prod, comp);
            } catch (Throwable t) {
                return null;
            }
        });
    }

    private static String queryBlock(Memory buffer, String subBlock) {
        try {
            PointerByReference strBuf = new PointerByReference();
            IntByReference strLen = new IntByReference();
            if (Version.INSTANCE.VerQueryValue(buffer, subBlock, strBuf, strLen) && strLen.getValue() > 0) {
                return strBuf.getValue().getWideString(0).trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Detects whether an active process is an unallowed app attempting to masquerade
     * as an allowed application by having its file renamed.
     *
     * @param processName    the running process name (e.g. "notepad.exe")
     * @param executablePath the full path of the running executable
     * @param allowedApp     the AllowedApp definition it claims to match (or null)
     * @return true if the process is a fake/renamed imposter, false if authentic
     */
    public static boolean isRenamedImposter(String processName, String executablePath, AllowedApp allowedApp) {
        if (processName == null || processName.isBlank() || executablePath == null || executablePath.isBlank()) {
            return false;
        }

        String proc = processName.trim().toLowerCase(Locale.ROOT);
        String path = executablePath.trim().toLowerCase(Locale.ROOT);
        String baseProc = proc.endsWith(".exe") ? proc : proc + ".exe";

        // 1. Check System-tool directory masquerade
        // If an app claims to be a Windows system tool (notepad, calc, cmd, etc.)
        // but is executing out of Downloads, Desktop, or Temp, it is a fake system app imposter!
        if (SYSTEM_TOOLS.contains(baseProc)) {
            boolean inSystemDir = path.contains("\\windows\\") || path.contains("/windows/")
                    || path.contains("\\program files\\windowsapps\\") || path.contains("/program files/windowsapps/");
            if (!inSystemDir) {
                for (String suspicious : SUSPICIOUS_DIRECTORIES) {
                    if (path.contains(suspicious)) {
                        return true; // Fake system app running out of downloads/temp/desktop
                    }
                }
            }
        }

        BinaryMetadata meta = readMetadata(executablePath);
        if (meta != null) {
            String prod = meta.productName() == null ? "" : meta.productName().toLowerCase(Locale.ROOT).trim();
            String desc = meta.fileDescription() == null ? "" : meta.fileDescription().toLowerCase(Locale.ROOT).trim();
            String orig = meta.originalFilename() == null ? "" : meta.originalFilename().toLowerCase(Locale.ROOT).trim();
            if (orig.endsWith(".mui")) {
                orig = orig.substring(0, orig.length() - 4);
            }
            if (!orig.isEmpty() && !orig.endsWith(".exe")) {
                orig = orig + ".exe";
            }

            String appName = allowedApp != null && allowedApp.name() != null
                    ? allowedApp.name().toLowerCase(Locale.ROOT).trim() : "";

            // A. If the PE metadata explicitly confirms the allowed app name, it is AUTHENTIC!
            // (e.g. VS Code has ProductName "Visual Studio Code", Java has "Java(TM) Platform")
            if (!appName.isEmpty()) {
                String cleanAppName = appName.replaceAll("[^a-z0-9]", "");
                String cleanProd = prod.replaceAll("[^a-z0-9]", "");
                String cleanDesc = desc.replaceAll("[^a-z0-9]", "");
                if (cleanAppName.length() >= 3 && (cleanProd.contains(cleanAppName) || cleanDesc.contains(cleanAppName))) {
                    return false; // Confirmed authentic
                }
            }

            // B. If the PE metadata reveals it is a known prohibited tool (e.g. Discord, Cheat Engine),
            // and the teacher's allowed app is NOT that tool, it is an IMPOSTER!
            for (String forbidden : PROHIBITED_SIGNATURES) {
                boolean metaHasForbidden = prod.contains(forbidden) || desc.contains(forbidden) || orig.contains(forbidden);
                if (metaHasForbidden && !appName.contains(forbidden)) {
                    return true; // Disguised prohibited app!
                }
            }

            // C. If OriginalFilename is present, is NOT a generic runtime wrapper,
            // and clearly contradicts the claimed process name:
            if (!orig.isEmpty() && !GENERIC_RUNTIMES.contains(orig)) {
                if (!orig.equalsIgnoreCase(baseProc)) {
                    return true;
                }
            }
        }

        return false;
    }
}
