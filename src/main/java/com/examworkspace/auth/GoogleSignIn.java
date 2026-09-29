package com.examworkspace.auth;

import com.examworkspace.db.Database;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google sign-in for a desktop app: OAuth 2.0 with PKCE and a localhost redirect.
 * The Desktop-app client ID is saved on this PC the first time it is used.
 */
public final class GoogleSignIn {

    public record Profile(String subject, String email, String name) {
    }

    public static class GoogleSignInException extends Exception {
        public GoogleSignInException(String message) {
            super(message);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private GoogleSignIn() {
    }

    public static Path settingsFile() {
        return Database.dataDir().resolve("google-oauth.properties");
    }

    public static String savedClientId() {
        String fromEnv = usable(System.getenv("EXAMWORKSPACE_GOOGLE_CLIENT_ID"));
        if (fromEnv != null) {
            return fromEnv;
        }
        String fromProperty = usable(System.getProperty("examworkspace.google.clientId", ""));
        if (fromProperty != null) {
            return fromProperty;
        }
        String fromFile = readClientId(settingsFile());
        if (fromFile != null) {
            return fromFile;
        }
        try (var in = GoogleSignIn.class.getResourceAsStream("/com/examworkspace/google-oauth.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return usable(properties.getProperty("clientId", ""));
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    static String usable(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String value = id.strip();
        String lower = value.toLowerCase();
        if (!lower.endsWith(".apps.googleusercontent.com") || lower.contains("xxxx")
                || lower.contains("example") || lower.contains("your-client") || value.contains(" ")) {
            return null;
        }
        return value;
    }

    private static String readClientId(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        return usable(properties.getProperty("clientId", ""));
    }

    public static void saveClientId(String clientId) throws IOException {
        String id = clientId == null ? "" : clientId.strip();
        if (!id.endsWith(".apps.googleusercontent.com") || id.contains(" ")) {
            throw new IOException("That does not look like a Google OAuth client ID.");
        }
        Properties properties = new Properties();
        properties.setProperty("clientId", id);
        try (var out = Files.newOutputStream(settingsFile())) {
            properties.store(out, "Google OAuth desktop client");
        }
    }

    public static Profile signIn(String clientId) throws GoogleSignInException {
        String verifier = randomUrlToken(32);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlToken(16);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new GoogleSignInException("Could not start the local sign-in listener.");
        }
        int port = server.getAddress().getPort();
        String redirect = "http://127.0.0.1:" + port + "/callback";
        CompletableFuture<String> code = new CompletableFuture<>();

        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String returnedState = queryValue(query, "state");
            String authCode = queryValue(query, "code");
            String error = queryValue(query, "error");
            byte[] page = html(error == null && state.equals(returnedState)
                    ? "Signed in. You can close this tab and return to Exam Workspace."
                    : "Sign-in did not finish. You can close this tab and try again.")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(page);
            }
            if (error != null) {
                code.completeExceptionally(new GoogleSignInException(
                        "access_denied".equals(error) ? "Google sign-in was cancelled." : "Google returned: " + error));
            } else if (!state.equals(returnedState) || authCode == null) {
                code.completeExceptionally(new GoogleSignInException("Google sign-in could not be verified."));
            } else {
                code.complete(authCode);
            }
        });
        server.start();
        try {
            URI auth = URI.create("https://accounts.google.com/o/oauth2/v2/auth"
                    + "?client_id=" + url(clientId)
                    + "&redirect_uri=" + url(redirect)
                    + "&response_type=code"
                    + "&scope=" + url("openid email profile")
                    + "&code_challenge=" + url(challenge)
                    + "&code_challenge_method=S256"
                    + "&state=" + url(state)
                    + "&prompt=select_account");
            openBrowser(auth);
            String authCode = code.get(3, TimeUnit.MINUTES);
            return profileFrom(clientId, redirect, verifier, authCode);
        } catch (TimeoutException e) {
            throw new GoogleSignInException("Timed out waiting for Google. The sign-in window may still be open.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof GoogleSignInException google) {
                throw google;
            }
            throw new GoogleSignInException(cause.getMessage() == null ? "Google sign-in failed." : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleSignInException("Google sign-in was interrupted.");
        } catch (IOException e) {
            throw new GoogleSignInException("Could not reach Google: " + e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static Profile profileFrom(String clientId, String redirect, String verifier, String authCode)
            throws IOException, InterruptedException, GoogleSignInException {
        String form = "code=" + url(authCode)
                + "&client_id=" + url(clientId)
                + "&redirect_uri=" + url(redirect)
                + "&grant_type=authorization_code"
                + "&code_verifier=" + url(verifier);
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> token = HTTP.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        if (token.statusCode() != 200) {
            String description = jsonString(token.body(), "error_description");
            throw new GoogleSignInException(description == null
                    ? "Google rejected the sign-in. Check that the client ID is a Desktop app client."
                    : description);
        }
        String accessToken = jsonString(token.body(), "access_token");
        if (accessToken == null) {
            throw new GoogleSignInException("Google did not return an access token.");
        }

        HttpRequest userRequest = HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> user = HTTP.send(userRequest, HttpResponse.BodyHandlers.ofString());
        if (user.statusCode() != 200) {
            throw new GoogleSignInException("Could not read the Google account profile.");
        }
        return new Profile(jsonString(user.body(), "sub"), jsonString(user.body(), "email"),
                jsonString(user.body(), "name"));
    }

    private static void openBrowser(URI uri) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }
        new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
    }

    private static String randomUrlToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return base64Url(buffer);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryValue(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String jsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String html(String message) {
        return "<!DOCTYPE html><html><body style=\"font-family:Segoe UI,sans-serif;padding:48px;color:#141a2b\">"
                + "<h2>" + message + "</h2></body></html>";
    }
}
