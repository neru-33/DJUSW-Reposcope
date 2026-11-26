package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class OperatorBootstrapStartupTests {
    private static final String USERNAME = "bootstrap" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorPasswords passwords;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("OPERATOR_BOOTSTRAP_USERNAME", () -> USERNAME);
        registry.add("OPERATOR_BOOTSTRAP_PASSWORD", () -> PASSWORD);
    }

    @Test
    void startupCreatesUsableTemporaryAdminAfterMigrations() throws Exception {
        var account = jdbc.queryForMap("SELECT u.id, c.password_hash, c.created_at, c.temp_password_expires_at "
                        + "FROM public.users u JOIN public.operator_credentials c ON c.user_id = u.id "
                        + "WHERE c.username = ? AND u.role = 'SUPER_ADMIN'", USERNAME);
        assertTrue(passwords.matches(PASSWORD, (String) account.get("password_hash")));
        var issued = ((java.sql.Timestamp) account.get("created_at")).toInstant();
        var expires = ((java.sql.Timestamp) account.get("temp_password_expires_at")).toInstant();
        assertEquals(Duration.ofHours(72), Duration.between(issued, expires));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM capstone.operator_bootstrap", Integer.class));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var login = mvc.perform(post("/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OperatorLoginController.LoginRequest(USERNAME, PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PASSWORD_CHANGE")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/projects").session(session)).andExpect(status().isUnauthorized());
    }
}
