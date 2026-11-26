package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorOtpSetupService {
    record Setup(String secret, String otpauth_uri) {
        @Override public String toString() { return "Setup[redacted]"; }
    }
    private record Account(String username, String encryptedSecret, boolean enabled, boolean mustChange) {}
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final OperatorOtpCrypto crypto;
    private final String credentials, sessions;

    OperatorOtpSetupService(JdbcTemplate jdbc, OperatorOtpCrypto crypto, @Value("${app.ingest-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid INGEST_SCHEMA");
        this.jdbc = jdbc;
        this.crypto = crypto;
        credentials = "\"" + schema + "\".operator_credentials";
        sessions = "\"" + schema + "\".operator_sessions";
    }

    @Transactional
    public Setup setup(OperatorLoginController.Pending pending, String sessionId) {
        var accounts = jdbc.query("SELECT username, totp_secret, totp_enabled_at IS NOT NULL, must_change_password FROM "
                        + credentials + " WHERE user_id = ? AND disabled_at IS NULL "
                        + "AND (expires_at IS NULL OR expires_at > now()) FOR UPDATE",
                (rs, n) -> new Account(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)), pending.userId());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + sessions
                        + " WHERE id_hash = ? AND user_id = ? AND stage = 'OTP_SETUP' AND expires_at > now() "
                        + "AND last_seen_at > now() - interval '30 minutes'",
                Integer.class, OperatorSessionService.hash(sessionId), pending.userId());
        if (accounts.isEmpty() || count == null || count != 1)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        Account account = accounts.getFirst();
        if (account.enabled() || account.mustChange())
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "OTP를 등록할 수 없는 계정 상태입니다.");
        String secret;
        if (account.encryptedSecret() == null) {
            secret = newSecret();
            jdbc.update("UPDATE " + credentials + " SET totp_secret = ? WHERE user_id = ?", crypto.encrypt(pending.userId(), secret), pending.userId());
        } else {
            secret = crypto.decrypt(pending.userId(), account.encryptedSecret());
        }
        String label = URLEncoder.encode("RepoScope:" + account.username(), StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=RepoScope&algorithm=SHA1&digits=6&period=30";
        return new Setup(secret, uri);
    }

    private String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        StringBuilder encoded = new StringBuilder(32);
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(BASE32[(buffer >>> bits) & 31]);
            }
        }
        return encoded.toString();
    }
}
