package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@DatabaseTest
class OperatorBootstrapTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OperatorPasswords passwords;
    private String ingest, api;
    private OperatorBootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ingest = "bootstrap_ingest_" + suffix;
        api = "bootstrap_api_" + suffix;
        Flyway.configure().dataSource(dataSource).schemas(ingest).defaultSchema(ingest)
                .locations("filesystem:../migrations").table("test_ingest_history")
                .placeholderReplacement(false).load().migrate();
        Flyway.configure().dataSource(dataSource).schemas(api).defaultSchema(api)
                .locations("classpath:db/migration").load().migrate();
        bootstrap = new OperatorBootstrapService(jdbc, passwords, ingest, api);
    }

    private <T> T transaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    @Test
    void firstAdminUsesHashedPasswordAndTemporaryExpiryExactly72HoursAfterIssue() {
        String username = "op" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        assertTrue(transaction(() -> bootstrap.initialize(username, password)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'", Integer.class));
        var account = jdbc.queryForMap("SELECT c.password_hash, c.must_change_password, c.created_at, c.temp_password_expires_at, "
                        + "c.totp_secret, c.expires_at FROM " + ingest + ".operator_credentials c WHERE username = ?", username);
        String stored = (String) account.get("password_hash");
        assertNotEquals(password, stored);
        assertTrue(passwords.matches(password, stored));
        assertEquals(Boolean.TRUE, account.get("must_change_password"));
        assertNull(account.get("totp_secret"));
        assertNull(account.get("expires_at"));
        Instant issued = ((Timestamp) account.get("created_at")).toInstant();
        Instant expires = ((Timestamp) account.get("temp_password_expires_at")).toInstant();
        assertEquals(Duration.ofHours(72), Duration.between(issued, expires));
        var beforeExpiry = new OperatorLoginService(jdbc, passwords, ingest, Clock.fixed(expires.minusNanos(1000), ZoneOffset.UTC));
        var atExpiry = new OperatorLoginService(jdbc, passwords, ingest, Clock.fixed(expires, ZoneOffset.UTC));
        assertEquals(OperatorLoginService.ResultType.OK, transaction(() -> beforeExpiry.login(username, password, "192.0.2.40")).type());
        assertEquals(OperatorLoginService.ResultType.EXPIRED, transaction(() -> atExpiry.login(username, password, "192.0.2.40")).type());
        assertFalse(transaction(() -> bootstrap.initialize(username, password)));
    }

    @Test
    void concurrentInitializationCreatesOnlyOneAndDeletionDoesNotAllowRecreation() throws Exception {
        String firstName = "first" + UUID.randomUUID().toString().replace("-", "");
        String secondName = "second" + UUID.randomUUID().toString().replace("-", "");
        String firstPassword = UUID.randomUUID().toString();
        String secondPassword = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return transaction(() -> bootstrap.initialize(firstName, firstPassword)); });
            var second = executor.submit(() -> { start.await(); return transaction(() -> bootstrap.initialize(secondName, secondPassword)); });
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + api + ".operator_bootstrap", Integer.class));
        jdbc.update("DELETE FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'");
        assertFalse(transaction(() -> bootstrap.initialize(firstName, firstPassword)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'", Integer.class));
    }

    @Test
    void existingAdminDisablesBootstrapPermanently() {
        jdbc.update("INSERT INTO " + ingest + ".users (login, role) VALUES (?, 'SUPER_ADMIN')", "existing-" + UUID.randomUUID());
        String username = "op" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        assertFalse(transaction(() -> bootstrap.initialize(username, password)));
        jdbc.update("DELETE FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'");
        assertFalse(transaction(() -> bootstrap.initialize(username, password)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + api + ".operator_bootstrap", Integer.class));
    }

    @Test
    void failedCreationRollsBackMarkerAndCanBeRetried() {
        String username = "op" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        long existing = jdbc.queryForObject("INSERT INTO " + ingest + ".users (login, role) VALUES (?, 'ADMIN') RETURNING id", Long.class, username);
        jdbc.update("INSERT INTO " + ingest + ".operator_credentials (user_id, username, password_hash) VALUES (?, ?, ?)",
                existing, username, passwords.hash(UUID.randomUUID().toString()));
        assertThrows(DataIntegrityViolationException.class, () -> transaction(() -> bootstrap.initialize(username, password)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + api + ".operator_bootstrap", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + ingest + ".users WHERE role = 'SUPER_ADMIN'", Integer.class));
        assertTrue(transaction(() -> bootstrap.initialize("new" + UUID.randomUUID().toString().replace("-", ""), password)));
    }
}
