package com.djusw.api;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_CONNECTION_TEST", matches = "true")
class DatabaseConnectionTests {
    @Autowired
    private DataSource dataSource;

    @Test
    void connectsUsingEnvironmentConfiguration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(5));
        }
    }
}
