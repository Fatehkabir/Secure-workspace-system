package com.examworkspace.auth;

import com.examworkspace.db.DataAccessException;
import com.examworkspace.db.Database;
import com.examworkspace.model.Teacher;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int MIN_PASSWORD_LENGTH = 6;

    public static class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    public Teacher register(String username, String fullName, char[] password) throws AuthException {
        try {
            String user = normalize(username);
            String name = fullName == null ? "" : fullName.strip();
            if (!USERNAME.matcher(user).matches()) {
                throw new AuthException("Username must be 3-64 characters: letters, digits, '.', '_' or '-'.");
            }
            if (name.isEmpty()) {
                throw new AuthException("Full name is required.");
            }
            if (password.length < MIN_PASSWORD_LENGTH) {
                throw new AuthException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }

            try (Connection conn = Database.connect()) {
                if (findHash(conn, user).isPresent()) {
                    throw new AuthException("That username is already taken.");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO teachers (username, full_name, password_hash) VALUES (?, ?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, user);
                    ps.setString(2, name);
                    ps.setString(3, PasswordHasher.hash(password));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return new Teacher(keys.getLong(1), user, name);
                    }
                }
            } catch (SQLException e) {
                throw new DataAccessException("Could not register teacher", e);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public Teacher login(String username, char[] password) throws AuthException {
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, full_name, password_hash FROM teachers WHERE username = ?")) {
            ps.setString(1, normalize(username));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordHasher.verify(password, rs.getString("password_hash"))) {
                    return new Teacher(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"));
                }
            }
            throw new AuthException("Invalid username or password.");
        } catch (SQLException e) {
            throw new DataAccessException("Could not log in", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Finds the teacher linked to this Google account, or creates one on the first sign-in. */
    public Teacher loginWithGoogle(String subject, String email, String fullName) throws AuthException {
        if (subject == null || subject.isBlank()) {
            throw new AuthException("Google did not return an account id.");
        }
        String name = fullName == null || fullName.isBlank() ? email : fullName.strip();
        if (name == null || name.isBlank()) {
            name = "Teacher";
        }
        try (Connection conn = Database.connect()) {
            Teacher existing = findGoogleTeacher(conn, subject, email);
            if (existing != null) {
                return existing;
            }
            String username = uniqueUsername(conn, email);
            char[] unused = new char[32];
            for (int i = 0; i < unused.length; i++) {
                unused[i] = (char) ('a' + RANDOM.nextInt(26));
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO teachers (username, full_name, password_hash, google_sub, email)
                    VALUES (?, ?, ?, ?, ?)""", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, name);
                ps.setString(3, PasswordHasher.hash(unused));
                ps.setString(4, subject);
                ps.setString(5, email);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Teacher(keys.getLong(1), username, name);
                }
            } finally {
                Arrays.fill(unused, '\0');
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not sign in with Google", e);
        }
    }

    private static Teacher findGoogleTeacher(Connection conn, String subject, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, username, full_name FROM teachers WHERE google_sub = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Teacher(rs.getLong(1), rs.getString(2), rs.getString(3));
                }
            }
        }
        if (email != null && !email.isBlank()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, username, full_name FROM teachers WHERE LOWER(email) = ?")) {
                ps.setString(1, email.strip().toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Teacher(rs.getLong(1), rs.getString(2), rs.getString(3));
                    }
                }
            }
        }
        return null;
    }

    private static String uniqueUsername(Connection conn, String email) throws SQLException {
        String base = "teacher";
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9._-]", "");
            if (local.length() >= 3) {
                base = local.substring(0, Math.min(local.length(), 50));
            }
        }
        String candidate = base;
        for (int suffix = 1; findHash(conn, candidate).isPresent(); suffix++) {
            candidate = base + suffix;
        }
        return candidate;
    }

    private static Optional<String> findHash(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT password_hash FROM teachers WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase();
    }
}
