package com.djusw.api.security;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorBootstrapService {
    private final JdbcTemplate jdbc;
    private final OperatorPasswords passwords;
    private final String users, credentials, marker;

    OperatorBootstrapService(JdbcTemplate jdbc, OperatorPasswords passwords,
                             @Value("${app.ingest-schema}") String ingestSchema,
                             @Value("${spring.flyway.default-schema:capstone}") String apiSchema) {
        if (!validSchema(ingestSchema) || !validSchema(apiSchema)) throw new IllegalArgumentException("Invalid database schema");
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.users = "\"" + ingestSchema + "\".users";
        this.credentials = "\"" + ingestSchema + "\".operator_credentials";
        this.marker = "\"" + apiSchema + "\".operator_bootstrap";
    }

    @Transactional
    public boolean initialize(String username, String password) {
        if (username == null || username.isBlank() || username.strip().length() > 255
                || password == null || password.isBlank() || password.length() > 1024)
            throw new IllegalArgumentException("초기 관리자 ID와 비밀번호 설정을 확인하세요.");
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        // 단일 행 삽입이 트랜잭션 사이에서 대기하므로 서버 여러 대가 동시에 시작해도 하나만 생성한다.
        int claimed = jdbc.update("INSERT INTO " + marker + " (singleton) VALUES (true) ON CONFLICT DO NOTHING");
        if (claimed == 0) return false;
        Boolean existing = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + users + " WHERE role = 'SUPER_ADMIN')", Boolean.class);
        if (Boolean.TRUE.equals(existing)) return false;
        String hash = passwords.hash(password);
        Long userId = jdbc.queryForObject("INSERT INTO " + users + " (login, role) VALUES (?, 'SUPER_ADMIN') RETURNING id",
                Long.class, normalized);
        jdbc.update("INSERT INTO " + credentials + " (user_id, username, password_hash, must_change_password, "
                        + "created_at, temp_password_expires_at) VALUES (?, ?, ?, true, now(), now() + interval '72 hours')",
                userId, normalized, hash);
        return true;
    }

    private static boolean validSchema(String schema) {
        return schema != null && schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}");
    }
}
