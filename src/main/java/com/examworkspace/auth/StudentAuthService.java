package com.examworkspace.auth;

import com.examworkspace.db.DataAccessException;
import com.examworkspace.db.Database;
import com.examworkspace.model.Student;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

public class StudentAuthService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    public static final int MIN_PASSWORD_LENGTH = AuthService.MIN_PASSWORD_LENGTH;

    public Student register(String username, String fullName, char[] password) throws AuthService.AuthException {
        try {
            String user = normalize(username);
            String name = fullName == null ? "" : fullName.strip();
            if (!USERNAME.matcher(user).matches()) {
                throw new AuthService.AuthException(
                        "Username must be 3-64 characters: letters, digits, '.', '_' or '-'.");
            }
            if (name.isEmpty()) {
                throw new AuthService.AuthException("Full name is required.");
            }
            if (password.length < MIN_PASSWORD_LENGTH) {
                throw new AuthService.AuthException(
                        "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }

            try (Connection conn = Database.connect()) {
                if (findHash(conn, user).isPresent()) {
                    throw new AuthService.AuthException("That username is already taken.");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO students (username, full_name, password_hash) VALUES (?, ?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, user);
                    ps.setString(2, name);
                    ps.setString(3, PasswordHasher.hash(password));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return new Student(keys.getLong(1), user, name);
                    }
                }
            } catch (SQLException e) {
                throw new DataAccessException("Could not register student", e);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public Student login(String username, char[] password) throws AuthService.AuthException {
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, full_name, password_hash FROM students WHERE username = ?")) {
            ps.setString(1, normalize(username));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordHasher.verify(password, rs.getString("password_hash"))) {
                    return new Student(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"));
                }
            }
            throw new AuthService.AuthException("Invalid username or password.");
        } catch (SQLException e) {
            throw new DataAccessException("Could not log in", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static Optional<String> findHash(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT password_hash FROM students WHERE username = ?")) {
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
