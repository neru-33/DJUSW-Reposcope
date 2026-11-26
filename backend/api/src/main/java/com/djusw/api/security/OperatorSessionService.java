package com.djusw.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorSessionService {
    private final JdbcTemplate jdbc;
    private final String credentials, sessions, users;

    OperatorSessionService(JdbcTemplate jdbc, @Value("${app.ingest-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        this.jdbc = jdbc;
        this.credentials = "\"" + schema + "\".operator_credentials";
        this.sessions = "\"" + schema + "\".operator_sessions";
        this.users = "\"" + schema + "\".users";
    }

    @Transactional
    public boolean register(OperatorLoginService.Result result, String sessionId, Instant expiresAt, String ip) {
        // 로그인 검증 후 비밀번호 변경이 먼저 확정된 경우 이전 비밀번호로 새 세션을 만들지 못하게 한다.
        var hashes = jdbc.query("SELECT password_hash FROM " + credentials + " WHERE user_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), result.userId());
        if (hashes.isEmpty() || !hashes.getFirst().equals(result.passwordHash())) return false;
        jdbc.update("INSERT INTO " + sessions + " (id_hash, user_id, stage, expires_at, ip) VALUES (?, ?, ?, ?, ?)",
                hash(sessionId), result.userId(), result.stage(), java.sql.Timestamp.from(expiresAt), ip);
        return true;
    }

    public boolean valid(String sessionId, OperatorLoginController.Pending pending, CurrentUser currentUser) {
        boolean active = "ACTIVE".equals(pending.stage());
        if (active && (currentUser == null || currentUser.id() != pending.userId())) return false;
        if (!active && currentUser != null) return false;
        return jdbc.update("UPDATE " + sessions + " s SET last_seen_at = now() FROM " + credentials + " c, " + users + " u "
                        + "WHERE s.id_hash = ? AND s.user_id = ? AND s.stage = ? AND s.expires_at > now() "
                        + "AND s.last_seen_at > now() - interval '30 minutes' AND c.user_id = s.user_id AND u.id = s.user_id "
                        + "AND c.disabled_at IS NULL AND u.disabled_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now()) "
                        + "AND u.role IN ('SUPER_ADMIN', 'ADMIN', 'PROFESSOR') AND (? = false OR u.role = ?)",
                hash(sessionId), pending.userId(), pending.stage(), active, active ? currentUser.role().name() : "") == 1;
    }

    public void remove(String sessionId) {
        jdbc.update("DELETE FROM " + sessions + " WHERE id_hash = ?", hash(sessionId));
    }

    public boolean rotateActive(String oldId, String newId, long userId) {
        return jdbc.update("UPDATE " + sessions + " SET id_hash = ? WHERE id_hash = ? AND user_id = ? "
                        + "AND stage = 'ACTIVE' AND expires_at > now() AND last_seen_at > now() - interval '30 minutes'",
                hash(newId), hash(oldId), userId) == 1;
    }

    static String hash(String sessionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
