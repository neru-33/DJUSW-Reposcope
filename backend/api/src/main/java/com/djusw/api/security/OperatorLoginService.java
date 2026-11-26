package com.djusw.api.security;

import java.time.Instant;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorLoginService {
    enum ResultType { OK, INVALID, EXPIRED, LOCKED, RATE_LIMITED }
    record Result(ResultType type, long userId, String stage, String passwordHash) {
        Result(ResultType type, long userId, String stage) { this(type, userId, stage, null); }
        static Result failure(ResultType type) { return new Result(type, 0, null, null); }
        @Override public String toString() { return "Result[type=" + type + ", userId=" + userId + ", stage=" + stage + ", passwordHash=redacted]"; }
    }
    private record Account(long id, String passwordHash, boolean mustChange, Instant tempExpires,
                           boolean otpEnabled, boolean disabled, Instant expires, Instant lockedUntil) {}

    private final JdbcTemplate jdbc;
    private final OperatorPasswords passwords;
    private final String credentials, users, attempts;
    private final String dummyHash;
    private final Clock clock;

    OperatorLoginService(JdbcTemplate jdbc, OperatorPasswords passwords, @Value("${app.ingest-schema}") String schema, Clock clock) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.credentials = "\"" + schema + "\".operator_credentials";
        this.users = "\"" + schema + "\".users";
        this.attempts = "\"" + schema + "\".login_attempts";
        this.dummyHash = passwords.hash(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public Result login(String username, String password, String ip) {
        // 같은 IP의 동시 요청도 제한을 우회할 수 없도록 트랜잭션 잠금을 잡는다.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NULL", Boolean.class, "operator-login:" + ip);
        Integer failures = jdbc.queryForObject("SELECT count(*) FROM " + attempts + " WHERE ip = ? AND at > now() - interval '15 minutes'", Integer.class, ip);
        if (failures != null && failures >= 20) return Result.failure(ResultType.RATE_LIMITED);
        var found = jdbc.query("SELECT c.user_id, c.password_hash, c.must_change_password, c.temp_password_expires_at, "
                        + "c.totp_enabled_at IS NOT NULL AS otp_enabled, (c.disabled_at IS NOT NULL OR u.disabled_at IS NOT NULL) AS disabled, "
                        + "c.expires_at, c.locked_until FROM " + credentials + " c JOIN " + users + " u ON u.id = c.user_id "
                        + "WHERE c.username = ? AND u.role IN ('SUPER_ADMIN', 'ADMIN', 'PROFESSOR')",
                (rs, n) -> new Account(rs.getLong("user_id"), rs.getString("password_hash"), rs.getBoolean("must_change_password"),
                        instant(rs.getTimestamp("temp_password_expires_at")), rs.getBoolean("otp_enabled"), rs.getBoolean("disabled"),
                        instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("locked_until"))), username.toLowerCase(Locale.ROOT));
        Account account = found.isEmpty() ? null : found.getFirst();
        boolean valid = passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        Instant now = Instant.now(clock);
        if (!valid || account == null || account.disabled() || (account.expires() != null && !now.isBefore(account.expires()))) {
            jdbc.update("INSERT INTO " + attempts + " (ip) VALUES (?)", ip);
            return Result.failure(ResultType.INVALID);
        }
        if (account.lockedUntil() != null && now.isBefore(account.lockedUntil())) {
            jdbc.update("INSERT INTO " + attempts + " (ip) VALUES (?)", ip);
            return Result.failure(ResultType.LOCKED);
        }
        if (account.mustChange() && (account.tempExpires() == null || !now.isBefore(account.tempExpires()))) {
            jdbc.update("INSERT INTO " + attempts + " (ip) VALUES (?)", ip);
            return Result.failure(ResultType.EXPIRED);
        }
        String stage = account.mustChange() ? "PASSWORD_CHANGE" : account.otpEnabled() ? "OTP" : "OTP_SETUP";
        return new Result(ResultType.OK, account.id(), stage, account.passwordHash());
    }

    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
}
