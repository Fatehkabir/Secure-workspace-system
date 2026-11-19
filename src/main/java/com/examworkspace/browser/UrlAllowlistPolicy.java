package com.examworkspace.browser;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public record UrlAllowlistPolicy(boolean enabled, boolean enforceHttps, boolean blockInternalPages, List<Rule> rules) {

    public static final Set<String> SPECIAL_SCHEMES = Set.of(
            "about", "chrome", "chrome-error", "edge", "file", "javascript", "data", "vbscript", "blob"
    );

    public UrlAllowlistPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static UrlAllowlistPolicy disabled() {
        return new UrlAllowlistPolicy(false, true, true, List.of());
    }

    public static UrlAllowlistPolicy parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return disabled();
        }

        List<Rule> parsed = new ArrayList<>();
        for (String line : serialized.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Rule rule = Rule.parse(line);
            if (rule != null) {
                parsed.add(rule);
            }
        }
        return new UrlAllowlistPolicy(true, true, true, parsed);
    }

    /**
     * Teacher-friendly parsing: each line is one allowed website, written either as a
     * plain domain ("exam.school.edu"), a domain with a path ("exam.school.edu/quiz")
     * or a full URL ("https://exam.school.edu/quiz"). Lines that still use the old
     * pipe format (HOST|example.com|https|true|false) keep working unchanged.
     * Empty lines and # comments are ignored.
     */
    public static List<Rule> parseFriendly(String text) {
        List<Rule> parsed = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parsed;
        }
        for (String rawLine : text.split("\\R")) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains("|")) {
                Rule legacy = Rule.parse(line);
                if (legacy != null) {
                    parsed.add(legacy);
                }
                continue;
            }
            line = line.replaceAll("^\\*+", "").trim();

            String scheme = "";
            if (line.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                int sep = line.indexOf("://");
                scheme = line.substring(0, sep).toLowerCase(Locale.ROOT);
                line = line.substring(sep + 3);
            }
            // strip credentials/ports/query/fragment, keep host + path
            line = line.split("[?#]", 2)[0];
            int atSign = line.indexOf('@');
            if (atSign >= 0) {
                line = line.substring(atSign + 1);
            }
            if (line.isEmpty()) {
                continue;
            }

            String host = line;
            String path = "";
            int slash = line.indexOf('/');
            if (slash >= 0) {
                host = line.substring(0, slash);
                path = normalizePath(line.substring(slash));
            }
            host = normalizeHost(host);
            if (host.isBlank() || !host.contains(".") && !"localhost".equals(host)) {
                continue;
            }

            if (!path.isBlank() && !"/".equals(path)) {
                // host + path: allow this path prefix on this host (subdomains included)
                String full = (scheme.isEmpty() ? "https" : scheme) + "://" + host + path;
                parsed.add(new Rule(Rule.Type.PATH_PREFIX, full, scheme, true, false));
            } else {
                // whole domain, subdomains included, any scheme (HTTPS toggle applies on top)
                parsed.add(new Rule(Rule.Type.HOST, host, scheme, true, false));
            }
        }
        return parsed;
    }

    /** One-line-per-rule human summary, shown under the teacher's editor. */
    public static String describeRules(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "No allowed websites yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (Rule rule : rules) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(switch (rule.type()) {
                case EXACT_URL -> "this exact page: ";
                case ORIGIN -> "this origin: ";
                case HOST -> "this site (+ subdomains): ";
                case SUBDOMAIN -> "these subdomains: ";
                case PATH_PREFIX -> "this site section: ";
            }).append(rule.match() == null || rule.match().isBlank() ? "?" : rule.match());
        }
        return sb.toString();
    }

    public String serialize() {
        if (rules.isEmpty()) {
            return "";
        }
        return rules.stream().map(Rule::serialize).collect(java.util.stream.Collectors.joining("\n"));
    }

    public static boolean isAllowed(String candidateUrl, UrlAllowlistPolicy policy) {
        if (candidateUrl == null || candidateUrl.isBlank() || policy == null || !policy.enabled()) {
            return false;
        }

        NormalizedUrl url = NormalizedUrl.parse(candidateUrl);
        if (url == null || !url.isUsable()) {
            return false;
        }

        if (policy.blockInternalPages && url.isSpecial()) {
            return false;
        }

        if (policy.enforceHttps && !"https".equalsIgnoreCase(url.scheme())) {
            return false;
        }

        if (policy.rules().isEmpty()) {
            return false;
        }

        for (Rule rule : policy.rules()) {
            if (matches(rule, url)) {
                return true;
            }
        }

        return false;
    }

    private static boolean matches(Rule rule, NormalizedUrl url) {
        if (rule == null) {
            return false;
        }
        String scheme = rule.scheme() == null ? "" : rule.scheme().trim().toLowerCase(Locale.ROOT);
        if (!scheme.isEmpty() && !scheme.equalsIgnoreCase(url.scheme())) {
            return false;
        }
        return switch (rule.type()) {
            case EXACT_URL -> exactMatch(rule.match(), url);
            case ORIGIN -> originMatch(rule.match(), url);
            case HOST -> hostMatch(rule.match(), url, rule.allowSubdomains());
            case SUBDOMAIN -> hostMatch(rule.match(), url, true);
            case PATH_PREFIX -> pathPrefixMatch(rule.match(), url);
        };
    }

    private static boolean exactMatch(String match, NormalizedUrl url) {
        if (match == null || match.isBlank()) {
            return false;
        }
        NormalizedUrl base = NormalizedUrl.parse(match);
        return base != null && base.equals(url);
    }

    private static boolean originMatch(String match, NormalizedUrl url) {
        if (match == null || match.isBlank()) {
            return false;
        }
        NormalizedUrl base = NormalizedUrl.parse(match);
        if (base == null) {
            return false;
        }
        return base.origin().equals(url.origin()) && base.host().equals(url.host());
    }

    private static boolean hostMatch(String host, NormalizedUrl url, boolean allowSubdomains) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String expected = normalizeHost(host);
        String actual = url.host();
        if (actual.equals(expected)) {
            return true;
        }
        String expectedApex = expected.startsWith("www.") ? expected.substring(4) : expected;
        String actualApex = actual.startsWith("www.") ? actual.substring(4) : actual;

        if (actualApex.equals(expectedApex)) {
            return true;
        }
        if (!allowSubdomains) {
            return false;
        }
        return actualApex.endsWith("." + expectedApex) || expectedApex.endsWith("." + actualApex);
    }

    public static String rootDomain(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String h = normalizeHost(host);
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        String[] parts = h.split("\\.");
        if (parts.length <= 2) {
            return h;
        }
        String secondToLast = parts[parts.length - 2];
        if (parts.length > 2 && (secondToLast.equals("co") || secondToLast.equals("com")
                || secondToLast.equals("edu") || secondToLast.equals("gov")
                || secondToLast.equals("org") || secondToLast.equals("net")
                || secondToLast.equals("ac"))
                && parts[parts.length - 1].length() == 2) {
            return parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static boolean pathPrefixMatch(String match, NormalizedUrl url) {
        if (match == null || match.isBlank()) {
            return false;
        }
        String prefix = match.trim();
        if (prefix.contains("://")) {
            NormalizedUrl base = NormalizedUrl.parse(prefix);
            if (base == null) {
                return false;
            }
            if (!hostMatch(base.host(), url, true)) {
                return false;
            }
            prefix = base.path();
        }

        prefix = normalizePath(prefix);
        if (prefix.isBlank() || "/".equals(prefix)) {
            return true;
        }

        String path = url.path();
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    public static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            return value;
        }
        try {
            return IDN.toASCII(value);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    public static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        String path = rawPath;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        String[] segments = path.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.addLast(segment);
        }

        String normalized = "/" + String.join("/", stack);
        return normalized.isBlank() ? "/" : normalized;
    }

    public record Rule(Type type, String match, String scheme, boolean allowSubdomains, boolean exactMatch) {
        public enum Type {
            EXACT_URL,
            ORIGIN,
            HOST,
            SUBDOMAIN,
            PATH_PREFIX
        }

        public String serialize() {
            return type.name() + "|" + (match == null ? "" : match) + "|"
                    + (scheme == null ? "" : scheme) + "|"
                    + allowSubdomains + "|" + exactMatch;
        }

        public static Rule parse(String serialized) {
            if (serialized == null || serialized.isBlank()) {
                return null;
            }
            String[] parts = serialized.split("\\|", -1);
            if (parts.length < 5) {
                return null;
            }
            try {
                Type type = Type.valueOf(parts[0].trim());
                return new Rule(type, parts[1], parts[2], Boolean.parseBoolean(parts[3]), Boolean.parseBoolean(parts[4]));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private record NormalizedUrl(String scheme, String host, int port, String path, String origin) {
        private static NormalizedUrl parse(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                return null;
            }

            String value = candidate.trim();
            if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                value = "https://" + value;
            }

            // Strip hash fragment and query before URI parsing so client-side SPA routing
            // (e.g. Socrative's /#/login/student or /student/#quiz) and unencoded query params
            // don't fail standard URI syntax parsing.
            String toParse = value;
            int hashIdx = toParse.indexOf('#');
            if (hashIdx >= 0) {
                toParse = toParse.substring(0, hashIdx);
            }
            int queryIdx = toParse.indexOf('?');
            if (queryIdx >= 0) {
                toParse = toParse.substring(0, queryIdx);
            }

            URI uri;
            try {
                uri = new URI(toParse);
            } catch (URISyntaxException e) {
                try {
                    int schemeSep = toParse.indexOf("://");
                    String scheme = toParse.substring(0, schemeSep).toLowerCase(Locale.ROOT);
                    String rest = toParse.substring(schemeSep + 3);
                    int slash = rest.indexOf('/');
                    String host = slash >= 0 ? rest.substring(0, slash) : rest;
                    String path = slash >= 0 ? rest.substring(slash) : "/";
                    int colon = host.indexOf(':');
                    int port = -1;
                    if (colon >= 0) {
                        try { port = Integer.parseInt(host.substring(colon + 1)); } catch (Exception ignored) {}
                        host = host.substring(0, colon);
                    }
                    String normHost = normalizeHost(host);
                    if (normHost.isBlank()) return null;
                    if (port == -1) port = defaultPortForScheme(scheme);
                    return new NormalizedUrl(scheme, normHost, port, normalizePath(path), scheme + "://" + normHost);
                } catch (Exception ex) {
                    return null;
                }
            }

            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                return null;
            }

            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.isBlank()) {
                return null;
            }

            if (!uri.isAbsolute()) {
                return null;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }

            String normalizedHost = normalizeHost(host);
            if (normalizedHost.isBlank()) {
                return null;
            }

            int port = uri.getPort();
            if (port == -1) {
                port = defaultPortForScheme(scheme);
            }

            String effectivePath = normalizePath(uri.getRawPath());
            String origin = scheme + "://" + normalizedHost + (port == defaultPortForScheme(scheme) ? "" : ":" + port);
            return new NormalizedUrl(scheme, normalizedHost, port, effectivePath, origin);
        }

        private boolean isUsable() {
            return scheme != null && !scheme.isBlank() && host != null && !host.isBlank() && !isSpecial();
        }

        private boolean isSpecial() {
            return SPECIAL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
        }

        private static int defaultPortForScheme(String scheme) {
            return switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http" -> 80;
                case "https" -> 443;
                default -> -1;
            };
        }
    }
}