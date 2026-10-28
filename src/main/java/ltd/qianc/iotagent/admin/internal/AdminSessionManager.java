package ltd.qianc.iotagent.admin.internal;

import ltd.qianc.iotagent.admin.AdminPasswordFile;
import ltd.qianc.iotagent.admin.AdminPasswordPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Independent cookie sessions for the admin panel; never reads PrincipalContext or Agent tokens. */
public final class AdminSessionManager {
    public static final String COOKIE = "runtime_admin";
    private static final long SESSION_MILLIS = Duration.ofHours(8).toMillis();
    private static final long ATTEMPT_MILLIS = Duration.ofMinutes(5).toMillis();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile String passwordHash;
    private volatile boolean changingPassword;
    private final int passwordMinimum;

    public AdminSessionManager(String password, int passwordMinimum) {
        this.passwordMinimum = passwordMinimum;
        if (password != null && !password.isBlank()) {
            this.passwordHash = AdminPasswordPolicy.hash(password);
        }
    }

    public synchronized LoginResult login(String ip, String password) {
        cleanup();
        Attempt attempt = attempts.computeIfAbsent(ip == null ? "unknown" : ip,
                ignored -> new Attempt(0, System.currentTimeMillis() + ATTEMPT_MILLIS));
        if (passwordHash == null) return LoginResult.unavailable();
        if (attempt.count >= 5 || sessions.size() >= 1000) return LoginResult.rateLimited();
        if (password == null || !AdminPasswordPolicy.matches(password, passwordHash)) {
            attempts.put(ip == null ? "unknown" : ip, new Attempt(attempt.count + 1, attempt.expires));
            return LoginResult.failed();
        }
        attempts.remove(ip == null ? "unknown" : ip);
        String token = randomHex(32);
        String csrf = randomHex(32);
        sessions.put(hash(token), new Session(csrf, System.currentTimeMillis() + SESSION_MILLIS));
        return LoginResult.success(token, csrf, (int) (SESSION_MILLIS / 1000));
    }

    public boolean valid(String token) {
        return session(token) != null;
    }

    public String csrf(String token) {
        Session session = session(token);
        return session == null ? null : session.csrf;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(hash(token));
    }

    public boolean matchesCurrent(String password) {
        return passwordHash != null && password != null && AdminPasswordPolicy.matches(password, passwordHash);
    }

    public synchronized PasswordResult changePassword(String current, String next, String confirmation, String ip) {
        return changePassword(current, next, confirmation, ip, null);
    }

    public synchronized PasswordResult changePassword(String current, String next, String confirmation, String ip,
            java.nio.file.Path passwordFile) {
        if (changingPassword) return PasswordResult.busy();
        if (passwordHash == null || current == null || !AdminPasswordPolicy.matches(current, passwordHash)) return PasswordResult.currentInvalid();
        try {
            AdminPasswordPolicy.validate(next, passwordMinimum);
            if (!next.equals(confirmation)) return PasswordResult.confirmationInvalid();
            changingPassword = true;
            if (passwordFile != null) AdminPasswordFile.write(passwordFile, next);
            passwordHash = AdminPasswordPolicy.hash(next);
            sessions.clear();
            attempts.clear();
            return PasswordResult.success();
        } catch (java.io.IOException e) {
            return PasswordResult.saveFailed();
        } catch (IllegalArgumentException e) {
            return PasswordResult.invalid(e.getMessage());
        } finally {
            changingPassword = false;
        }
    }

    public void clear() {
        sessions.clear();
        attempts.clear();
    }

    public int minimum() {
        return passwordMinimum;
    }

    private Session session(String token) {
        if (token == null || !token.matches("[a-fA-F0-9]{64}")) return null;
        Session value = sessions.get(hash(token));
        if (value == null || value.expires <= System.currentTimeMillis()) {
            if (token != null) sessions.remove(hash(token));
            return null;
        }
        return value;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        attempts.entrySet().removeIf(entry -> entry.getValue().expires <= now);
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        StringBuilder result = new StringBuilder(bytes * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Session(String csrf, long expires) { }
    private record Attempt(int count, long expires) { }

    public record LoginResult(int status, String code, String token, String csrf, int maxAge) {
        static LoginResult success(String token, String csrf, int maxAge) { return new LoginResult(200, null, token, csrf, maxAge); }
        static LoginResult failed() { return new LoginResult(401, "ADMIN_LOGIN_FAILED", null, null, 0); }
        static LoginResult rateLimited() { return new LoginResult(429, "ADMIN_RATE_LIMIT", null, null, 0); }
        static LoginResult unavailable() { return new LoginResult(503, "ADMIN_UNAVAILABLE", null, null, 0); }
    }

    public record PasswordResult(int status, String code, String message) {
        static PasswordResult success() { return new PasswordResult(200, null, null); }
        static PasswordResult busy() { return new PasswordResult(409, "ADMIN_PASSWORD_BUSY", "密码正在修改，请稍后重试"); }
        static PasswordResult currentInvalid() { return new PasswordResult(400, "ADMIN_PASSWORD_INVALID", "当前密码不正确"); }
        static PasswordResult confirmationInvalid() { return new PasswordResult(400, "ADMIN_PASSWORD_MISMATCH", "两次新密码不一致"); }
        static PasswordResult invalid(String message) { return new PasswordResult(400, "ADMIN_PASSWORD_INVALID", message); }
        static PasswordResult saveFailed() { return new PasswordResult(503, "ADMIN_PASSWORD_SAVE_FAILED", "密码未能保存，请稍后重试"); }
    }
}
