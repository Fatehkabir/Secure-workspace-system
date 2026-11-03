package com.examworkspace.alerts;

import com.examworkspace.monitor.AllowedApp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server using com.sun.net.httpserver (already required by the
 * project's module-info.java as jdk.httpserver -- no new dependency needed).
 *
 * <p>Exposes:
 * <pre>
 *   POST /api/alerts   { "studentId": "…", "detectedApp": "…", "executablePath": "…" }
 *   GET  /api/allowed  → { "roomName": "…", "allowed": ["chrome.exe", …] }
 *   GET  /health       → "OK"
 * </pre>
 *
 * <p>JSON is parsed by hand with a small regex helper, since this project has no
 * JSON library dependency — fine for this fixed, flat schema.
 *
 * <p>The {@code /api/allowed} endpoint lets the student client auto-fetch the
 * room's allowed process-name list instead of having the teacher type it
 * manually. The list is re-read from the database on every request, so it
 * reflects apps added or removed in the main Exam Workspace app without
 * requiring the teacher to restart the monitor.
 */
public class AlertServer {

    private final RoomLookup.RoomInfo room;
    private final AlertStore store;
    private HttpServer server;

    // Mutable reference so we can refresh the allowed list live.
    private volatile RoomLookup.RoomInfo currentRoom;
    private volatile List<AllowedApp> currentApps = List.of();

    // Students currently locked (by studentId). A student is locked the
    // moment their violation alert arrives, and stays locked until the
    // teacher calls /api/unlock for them -- this set is the source of truth
    // both the student's poller and the teacher's UI rely on.
    private final Set<String> lockedStudents = ConcurrentHashMap.newKeySet();

    public AlertServer(RoomLookup.RoomInfo room, AlertStore store) {
        this.room = room;
        this.currentRoom = room;
        this.store = store;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/alerts",  this::handleAlert);
        server.createContext("/api/allowed", this::handleAllowed);
        server.createContext("/api/join",    this::handleJoin);
        server.createContext("/api/lock-status", this::handleLockStatus);
        server.createContext("/api/unlock",      this::handleUnlock);
        server.createContext("/health", exchange -> respond(exchange, 200, "\"OK\""));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── /api/alerts ───────────────────────────────────────────────────────────

    private void handleAlert(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "\"Method Not Allowed\"");
            return;
        }

        try {
            // Refresh the allowed list from the DB on every alert so newly added
            // apps are respected immediately without restarting the monitor.
            refreshAllowed();

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = parseFlatJson(body);

            String studentId      = fields.getOrDefault("studentId",      "unknown-student");
            String detectedApp    = fields.getOrDefault("detectedApp",    "unknown-app");
            String executablePath = fields.getOrDefault("executablePath", "");
            String screenshot     = fields.get("screenshotBase64"); // may be absent/null

            System.out.println("[AlertServer] Received alert: student=" + studentId
                    + " app=" + detectedApp
                    + " screenshot=" + (screenshot == null ? "none" : screenshot.length() + " base64 chars")
                    + " bodyLength=" + body.length());

            boolean allowed = false; // the student client only sends apps it judged NOT allowed
            AlertRecord record = new AlertRecord(studentId, detectedApp, executablePath, allowed, Instant.now(), screenshot);
            store.add(record);

            // Every violation alert locks that student until the teacher unlocks them.
            lockedStudents.add(studentId);
            System.out.println("[AlertServer] Locked student: " + studentId);

            respond(exchange, 200, "{\"received\":true,\"allowed\":" + allowed + "}");
        } catch (Throwable ex) {
            System.err.println("[AlertServer] handleAlert failed:");
            ex.printStackTrace();
            String msg = escapeJson(String.valueOf(ex.getMessage()));
            respond(exchange, 500, "{\"received\":false,\"error\":\"" + msg + "\"}");
        }
    }

    // ── /api/allowed ──────────────────────────────────────────────────────────

    /**
     * Returns the current allowed process-name list as JSON.
     * The student client calls this once at startup so it doesn't need the
     * teacher to type the list manually — it is always in sync with the DB.
     *
     * <p>Response: {@code { "roomName": "…", "allowed": ["chrome.exe", …] }}
     */
    private void handleAllowed(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "\"Method Not Allowed\"");
            return;
        }
        try {
            refreshAllowed();
            List<String> names = currentRoom.allowedProcessNames();
            StringBuilder json = new StringBuilder();
            json.append("{\"roomName\":\"").append(escapeJson(currentRoom.name())).append("\",");
            json.append("\"roomCode\":\"").append(escapeJson(currentRoom.roomCode())).append("\",");
            json.append("\"allowed\":[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(escapeJson(names.get(i))).append('"');
            }
            json.append("],").append(appsJson()).append("}");
            respond(exchange, 200, json.toString());
        } catch (Exception ex) {
            System.err.println("[AlertServer] handleAllowed failed:");
            ex.printStackTrace();
            respond(exchange, 500, "{\"error\":\"" + escapeJson(String.valueOf(ex.getMessage())) + "\"}");
        }
    }

    // ── /api/join ─────────────────────────────────────────────────────────────

    /**
     * Lets a student "join" this room from another machine: checks the room
     * code matches the room THIS server instance is monitoring, checks the
     * password, and if both pass, returns the current allowed-app list so
     * the student client doesn't need a second round trip.
     *
     * Request:  { "studentId": "…", "roomCode": "…", "roomPassword": "…" }
     * Response: { "success": true, "roomName": "…", "allowed": ["chrome.exe", …] }
     *        or { "success": false, "message": "…" }  (HTTP 404 or 401)
     */
    private void handleJoin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }

        refreshAllowed();

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = parseFlatJson(body);
        String studentId = fields.getOrDefault("studentId", "");
        String roomCode = fields.getOrDefault("roomCode", "");
        String roomPassword = fields.getOrDefault("roomPassword", "");

        if (!currentRoom.roomCode().equalsIgnoreCase(roomCode)) {
            respond(exchange, 404, "{\"success\":false,\"message\":\"No room with that code is being monitored on this server.\"}");
            return;
        }

        boolean passwordOk;
        try {
            passwordOk = RoomLookup.checkPassword(currentRoom.roomId(), roomPassword);
        } catch (SQLException e) {
            respond(exchange, 500, "{\"success\":false,\"message\":\"Server error checking the password.\"}");
            return;
        }
        if (!passwordOk) {
            respond(exchange, 401, "{\"success\":false,\"message\":\"Incorrect room password.\"}");
            return;
        }

        List<String> names = currentRoom.allowedProcessNames();
        boolean locked = !studentId.isEmpty() && lockedStudents.contains(studentId);
        StringBuilder json = new StringBuilder();
        json.append("{\"success\":true,\"roomName\":\"").append(escapeJson(currentRoom.name())).append("\",");
        json.append("\"locked\":").append(locked).append(',');
        json.append("\"allowed\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escapeJson(names.get(i))).append('"');
        }
        json.append("],").append(appsJson()).append("}");
        respond(exchange, 200, json.toString());
    }

    // ── /api/lock-status ─────────────────────────────────────────────────────

    /**
     * Polled by a locked student every ~2s to find out whether the teacher
     * has unlocked them yet. GET /api/lock-status?studentId=...
     * Response: { "locked": true|false }
     */
    private void handleLockStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String studentId = queryParam(exchange, "studentId");
            boolean locked = studentId != null && lockedStudents.contains(studentId);
            respond(exchange, 200, "{\"locked\":" + locked + "}");
        } catch (Exception ex) {
            System.err.println("[AlertServer] handleLockStatus failed:");
            ex.printStackTrace();
            respond(exchange, 500, "{\"error\":\"" + escapeJson(String.valueOf(ex.getMessage())) + "\"}");
        }
    }

    // ── /api/unlock ───────────────────────────────────────────────────────────

    /**
     * Called by the teacher's Live Monitor panel to release a locked student.
     * POST /api/unlock  { "studentId": "…" }
     */
    private void handleUnlock(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = parseFlatJson(body);
            String studentId = fields.getOrDefault("studentId", "");
            boolean removed = lockedStudents.remove(studentId);
            System.out.println("[AlertServer] Unlock requested for " + studentId + " (was locked: " + removed + ")");
            respond(exchange, 200, "{\"success\":true}");
        } catch (Exception ex) {
            System.err.println("[AlertServer] handleUnlock failed:");
            ex.printStackTrace();
            respond(exchange, 500, "{\"success\":false,\"message\":\"" + escapeJson(String.valueOf(ex.getMessage())) + "\"}");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Extracts a single query-string parameter from a GET request's URI. */
    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Reloads the allowed process names from the database. Swaps
     * {@link #currentRoom} atomically so concurrent requests see a consistent
     * snapshot. Silently ignores database errors (keeps stale list).
     */
    private void refreshAllowed() {
        try {
            currentApps = RoomLookup.loadAllowedApps(room.roomId());
            List<String> fresh = RoomLookup.reloadAllowedProcessNames(room.roomId());
            currentRoom = new RoomLookup.RoomInfo(
                    room.roomId(), room.name(), room.roomCode(), fresh);
        } catch (SQLException ignored) {
            // Keep the previous list; the monitor stays functional.
        }
    }

    private String appsJson() {
        StringBuilder sb = new StringBuilder("\"apps\":[");
        List<AllowedApp> apps = currentApps;
        for (int i = 0; i < apps.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(apps.get(i).encode())).append('"');
        }
        return sb.append(']').toString();
    }

    private Map<String, String> parseFlatJson(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null) return result;
        int i = 0;
        int len = json.length();
        while (i < len) {
            int keyStartQuote = json.indexOf('"', i);
            if (keyStartQuote == -1) break;
            int keyEndQuote = findEndQuote(json, keyStartQuote + 1);
            if (keyEndQuote == -1) break;
            String key = json.substring(keyStartQuote + 1, keyEndQuote);

            int colon = json.indexOf(':', keyEndQuote + 1);
            if (colon == -1) break;

            int valStart = colon + 1;
            while (valStart < len && Character.isWhitespace(json.charAt(valStart))) {
                valStart++;
            }
            if (valStart >= len) break;

            if (json.charAt(valStart) == '"') {
                int valEndQuote = findEndQuote(json, valStart + 1);
                if (valEndQuote == -1) break;
                String val = unescapeJson(json.substring(valStart + 1, valEndQuote));
                result.put(key, val);
                i = valEndQuote + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < len && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}' && !Character.isWhitespace(json.charAt(valEnd))) {
                    valEnd++;
                }
                result.put(key, json.substring(valStart, valEnd));
                i = valEnd;
            }
        }
        return result;
    }

    private static int findEndQuote(String s, int fromIndex) {
        int i = fromIndex;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        if (s.indexOf('\\') == -1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
