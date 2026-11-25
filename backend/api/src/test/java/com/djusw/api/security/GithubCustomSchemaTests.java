package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@DatabaseTest
@TestPropertySource(properties = "app.ingest-schema=account_test")
class GithubCustomSchemaTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired GithubUserRepository users;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS account_test");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_test.users (id bigserial PRIMARY KEY, github_id bigint UNIQUE, "
                + "login text NOT NULL, role text NOT NULL DEFAULT 'GUEST', name text, student_id text UNIQUE, "
                + "department text, consented_at timestamptz, registered_at timestamptz, disabled_at timestamptz)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_test.user_repos (user_id bigint NOT NULL REFERENCES account_test.users(id), "
                + "repo_id bigint NOT NULL CHECK (repo_id <> 999), checked_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (user_id, repo_id))");
        jdbc.update("DELETE FROM account_test.user_repos");
        jdbc.update("DELETE FROM account_test.users");
    }

    @Test
    void refreshesConfiguredSchemaAndRollsBackAllChangesOnWriteFailure() {
        var first = users.saveLogin(new GithubIdentityClient.GithubIdentity(987650010L, "first", Set.of(10L, 20L)));
        var second = users.saveLogin(new GithubIdentityClient.GithubIdentity(987650010L, "second", Set.of(20L, 30L)));
        assertEquals(first.id(), second.id());
        assertEquals(Set.of(20L, 30L), Set.copyOf(jdbc.queryForList(
                "SELECT repo_id FROM account_test.user_repos WHERE user_id = ?", Long.class, first.id())));
        assertThrows(DataAccessException.class, () -> users.saveLogin(
                new GithubIdentityClient.GithubIdentity(987650010L, "should-rollback", Set.of(999L))));
        assertEquals("second", jdbc.queryForObject("SELECT login FROM account_test.users WHERE id = ?", String.class, first.id()));
        assertEquals(Set.of(20L, 30L), Set.copyOf(jdbc.queryForList(
                "SELECT repo_id FROM account_test.user_repos WHERE user_id = ?", Long.class, first.id())));
        users.saveLogin(new GithubIdentityClient.GithubIdentity(987650010L, "second", Set.of()));
        assertEquals(List.of(), jdbc.queryForList("SELECT repo_id FROM account_test.user_repos WHERE user_id = ?", Long.class, first.id()));
    }
}
