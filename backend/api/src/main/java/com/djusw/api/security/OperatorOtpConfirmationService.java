package com.djusw.api.security;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorOtpConfirmationService {
    enum Status { OK, UNAUTHORIZED, FORBIDDEN, INVALID, LOCKED, RATE_LIMITED }
    record Result(Status status, CurrentUser user) {
        static Result error(Status status) { return new Result(status, null); }
    }
    private record Account(String secret, boolean enabled, boolean mustChange, Long lastStep,
                           Instant lockedUntil, CurrentUser.Role role) {}
    private final JdbcTemplate jdbc;
    private final OperatorOtpCrypto crypto;
    private final OperatorTotp totp;
    private final String credentials, users, sessions, attempts;

    OperatorOtpConfirmationService(JdbcTemplate jdbc, OperatorOtpCrypto crypto, OperatorTotp totp,
                                   @Value("${app.ingest-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.totp = totp;
        String prefix = "\"" + schema + "\".";
        credentials = prefix + "operator_credentials";
        users = prefix + "users";
        sessions = prefix + "operator_sessions";
        attempts = prefix + "login_attempts";
    }

    @Transactional
    public Result confirm(OperatorLoginController.Pending pending, String sessionId, String code, String ip) {
        boolean setup = "OTP_SETUP".equals(pending.stage());
        if (!setup && !"OTP".equals(pending.stage())) return Result.error(Status.FORBIDDEN);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NULL", Boolean.class, "operator-login:" + ip);
        Integer failures = jdbc.queryForObject("SELECT count(*) FROM " + attempts + " WHERE ip = ? AND at > now() - interval '15 minutes'", Integer.class, ip);
        if (failures != null && failures >= 20) return Result.error(Status.RATE_LIMITED);
        List<Account> accounts = jdbc.query("SELECT c.totp_secret, c.totp_enabled_at IS NOT NULL, c.must_change_password, "
                        + "c.totp_last_step, c.locked_until, u.role FROM " + credentials + " c JOIN " + users + " u ON u.id = c.user_id "
                        + "WHERE c.user_id = ? AND c.disabled_at IS NULL AND u.disabled_at IS NULL "
                        + "AND (c.expires_at IS NULL OR c.expires_at > now()) AND u.role IN ('SUPER_ADMIN', 'ADMIN', 'PROFESSOR') FOR UPDATE OF c",
                (rs, row) -> new Account(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getObject(4, Long.class),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), CurrentUser.Role.valueOf(rs.getString(6))), pending.userId());
        if (accounts.isEmpty()) return Result.error(Status.UNAUTHORIZED);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + sessions
                        + " WHERE id_hash = ? AND user_id = ? AND stage = ? AND expires_at > now() "
                        + "AND last_seen_at > now() - interval '30 minutes'",
                Integer.class, OperatorSessionService.hash(sessionId), pending.userId(), pending.stage());
        if (count == null || count != 1) return Result.error(Status.UNAUTHORIZED);
        Account account = accounts.getFirst();
        if (account.mustChange() || account.enabled() == setup || account.secret() == null) return Result.error(Status.FORBIDDEN);
        Instant now = Instant.now();
        if (account.lockedUntil() != null && now.isBefore(account.lockedUntil())) {
            jdbc.update("INSERT INTO " + attempts + " (ip) VALUES (?)", ip);
            return Result.error(Status.LOCKED);
        }
        Long step = totp.matchingStep(crypto.decrypt(pending.userId(), account.secret()), code, now, account.lastStep());
        if (step == null) {
            jdbc.update("INSERT INTO " + attempts + " (ip) VALUES (?)", ip);
            jdbc.update("UPDATE " + credentials + " SET otp_failed_attempts = CASE WHEN locked_until IS NOT NULL AND locked_until <= now() "
                            + "THEN 1 ELSE otp_failed_attempts + 1 END, locked_until = CASE WHEN "
                            + "(CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1 ELSE otp_failed_attempts + 1 END) >= 5 "
                            + "THEN now() + interval '15 minutes' ELSE NULL END WHERE user_id = ?", pending.userId());
            return Result.error(Status.INVALID);
        }
        jdbc.update("UPDATE " + credentials + " SET totp_enabled_at = CASE WHEN ? THEN now() ELSE totp_enabled_at END, "
                + "totp_last_step = ?, otp_failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE user_id = ?",
                setup, step, pending.userId());
        jdbc.update("UPDATE " + sessions + " SET stage = 'ACTIVE', last_seen_at = now() WHERE id_hash = ? AND user_id = ?",
                OperatorSessionService.hash(sessionId), pending.userId());
        return new Result(Status.OK, new CurrentUser(pending.userId(), account.role()));
    }
}
