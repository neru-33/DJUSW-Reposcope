package com.djusw.api;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.djusw.api.support.DatabaseTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DatabaseTest
class DatabaseConnectionTests {
    @Autowired
    private DataSource dataSource;

    @Test
    void connectsToTestDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(5));
        }
    }
}
