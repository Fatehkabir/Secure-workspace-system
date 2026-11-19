package com.examworkspace.browser;

import java.util.Locale;
import java.util.Set;

/**
 * Knows which foreground processes are web browsers and which URLs are
 * "internal" browser pages (new tab, settings, blank) rather than real sites.
 *
 * <p>Used by the foreground-app check so the teacher's website allowlist can be
 * enforced against the page the student is actually on, instead of treating the
 * whole browser as one allowed (or forbidden) app.
 */
public final class BrowserProcesses {

    /** Browser executables, without the ".exe" suffix, lower-case. */
    private static final Set<String> BROWSERS = Set.of(
            "chrome", "chromium", "msedge", "edge", "brave", "firefox",
            "vivaldi", "opera", "opera_gx", "whale", "yandex", "librewolf",
            "waterfox", "thorium", "iridium", "slimjet", "epic", "tor"
    );

    /** URL schemes that are browser-internal pages, not real websites. */
    private static final Set<String> INTERNAL_SCHEMES = Set.of(
            "about", "chrome", "chrome-extension", "chromewebdata", "edge", "edge-extension",
            "brave", "opera", "vivaldi", "view-source", "devtools", "moz-extension",
            "browser", "whale", "yandex"
    );

    private BrowserProcesses() {
    }

    /**
     * @param processName foreground executable name, e.g. "chrome.exe" or "chrome"
     * @return true when this is a web browser the URL allowlist applies to
     */
    public static boolean isBrowser(String processName) {
        return BROWSERS.contains(baseName(processName));
    }

    /**
     * True for browser-internal pages (new tab, blank tab, browser settings) that
     * should never be flagged and never count as an allowed website: the student
     * has the browser open but has not navigated anywhere yet.
     */
    public static boolean isInternalUrl(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (u.isEmpty()) {
            return false;
        }
        int colon = u.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = u.substring(0, colon);
        if (!INTERNAL_SCHEMES.contains(scheme)) {
            return false;
        }
        String rest = u.substring(colon + 1);
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        // Blank/new-tab pages are "idle"; every other internal page (settings,
        // history, downloads, extensions) is treated the same way: not a website,
        // and never a violation on its own.
        return true;
    }

    /** True for the empty new-tab/blank pages a browser shows before any navigation. */
    public static boolean isNewTabPage(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (u.isEmpty()) {
            return true;
        }
        return u.equals("about:blank") || u.equals("about:home") || u.equals("about:newtab")
                || u.contains("newtab") || u.contains("new-tab");
    }

    /**
     * True when the string plausibly is a web URL. The address bar normally
     * reports the full URL ("https://exam.school.edu/quiz"); some browsers report
     * it without the scheme ("exam.school.edu/quiz"), which still matches here.
     */
    public static boolean looksLikeUrl(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > 2048) {
            return false;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.matches("^[a-z][a-z0-9+.-]*://.*")) {
            return !INTERNAL_SCHEMES.contains(lower.substring(0, lower.indexOf(':'))) || isInternalUrl(v);
        }
        if (lower.startsWith("www.")) {
            return true;
        }
        // host/path with a dot in the host part and no whitespace
        int slash = lower.indexOf('/');
        String host = slash >= 0 ? lower.substring(0, slash) : lower;
        return host.contains(".") && !host.contains(" ") && !host.isEmpty();
    }

    /** Adds "https://" in front of scheme-less address-bar values so the policy parser accepts them. */
    public static String withScheme(String url) {
        String v = url == null ? "" : url.trim();
        if (v.isEmpty()) {
            return v;
        }
        return v.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") ? v : "https://" + v;
    }

    private static String baseName(String processName) {
        if (processName == null) {
            return "";
        }
        String name = processName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        int sep = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        return sep >= 0 ? name.substring(sep + 1) : name;
    }
}
