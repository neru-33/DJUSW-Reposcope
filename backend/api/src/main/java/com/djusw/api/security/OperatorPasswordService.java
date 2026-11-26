package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorPasswordService {
    private final JdbcTemplate jdbc;
    private final OperatorPasswords passwords;
    private final String credentials, users, sessions;

    OperatorPasswordService(JdbcTemplate jdbc, OperatorPasswords passwords, @Value("${app.ingest-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        this.jdbc = jdbc;
        this.passwords = passwords;
        credentials = "\"" + schema + "\".operator_credentials";
        users = "\"" + schema + "\".users";
        sessions = "\"" + schema + "\".operator_sessions";
    }

    @Transactional
    public String change(OperatorLoginController.Pending pending, String sessionId, String currentPassword,
                         String newPassword, String newUsername) {
        var accounts = jdbc.query("SELECT password_hash, must_change_password, temp_password_expires_at, totp_enabled_at IS NOT NULL, username "
                        + "FROM " + credentials + " WHERE user_id = ? AND disabled_at IS NULL "
                        + "AND (expires_at IS NULL OR expires_at > now()) FOR UPDATE",
                (rs, row) -> new Account(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3), rs.getBoolean(4), rs.getString(5)), pending.userId());
        if (accounts.isEmpty()) throw error(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        Account account = accounts.getFirst();
        // 먼저 계정을 잠근 뒤 세션을 확인하여 동시 변경 및 철회된 세션의 재사용을 막는다.
        Integer sessionCount = jdbc.queryForObject("SELECT count(*) FROM " + sessions
                + " WHERE id_hash = ? AND user_id = ? AND stage = ? AND expires_at > now() AND last_seen_at > now() - interval '30 minutes'",
                Integer.class, OperatorSessionService.hash(sessionId), pending.userId(), pending.stage());
        if (sessionCount == null || sessionCount != 1) throw error(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        if (!passwords.matches(currentPassword, account.passwordHash()))
            throw error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "현재 비밀번호가 올바르지 않습니다.");
        if (currentPassword.equals(newPassword))
            throw error(HttpStatus.BAD_REQUEST, "invalid_request", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        boolean temporary = pending.stage().equals("PASSWORD_CHANGE");
        if (temporary != account.mustChange()) throw error(HttpStatus.FORBIDDEN, "forbidden", "변경할 수 없는 계정 상태입니다.");
        if (temporary && (account.tempExpires() == null || !Instant.now().isBefore(account.tempExpires().toInstant())))
            throw error(HttpStatus.UNAUTHORIZED, "temp_password_expired", "임시 비밀번호가 만료되었습니다.");
        String username = null;
        if (temporary) {
            if (newUsername == null || newUsername.isBlank() || newUsername.length() > 255)
                throw error(HttpStatus.BAD_REQUEST, "invalid_request", "새 ID를 확인하세요.");
            username = newUsername.strip().toLowerCase(Locale.ROOT);
            if (username.equals(account.username()))
                throw error(HttpStatus.BAD_REQUEST, "invalid_request", "새 ID는 현재 ID와 달라야 합니다.");
        } else if (newUsername != null && !newUsername.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_request", "ID는 임시 계정 설정에서만 변경할 수 있습니다.");
        }
        String nextStage = temporary ? (account.otpEnabled() ? "OTP" : "OTP_SETUP") : "ACTIVE";
        try {
            jdbc.update("UPDATE " + credentials + " SET username = COALESCE(?, username), password_hash = ?, "
                            + "must_change_password = false, temp_password_expires_at = NULL, password_changed_at = now() WHERE user_id = ?",
                    username, passwords.hash(newPassword), pending.userId());
            if (temporary) jdbc.update("UPDATE " + users + " SET login = ? WHERE id = ?", username, pending.userId());
            jdbc.update("DELETE FROM " + sessions + " WHERE user_id = ? AND id_hash <> ?", pending.userId(), OperatorSessionService.hash(sessionId));
            jdbc.update("UPDATE " + sessions + " SET stage = ?, last_seen_at = now() WHERE id_hash = ? AND user_id = ?",
                    nextStage, OperatorSessionService.hash(sessionId), pending.userId());
        } catch (DuplicateKeyException ex) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_request", "이미 사용 중인 ID입니다.");
        }
        return nextStage;
    }

    private record Account(String passwordHash, boolean mustChange, Timestamp tempExpires, boolean otpEnabled, String username) {}
    private static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}
