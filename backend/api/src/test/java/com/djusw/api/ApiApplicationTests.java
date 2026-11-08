package com.djusw.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"APP_VERSION=test-image-tag", "DB_USER=test", "DB_PASSWORD=test"})
class ApiApplicationTests {

	@Autowired
	private HealthController healthController;

	@Test
	void contextLoads() {
	}

	@Test
	void returnsConfiguredVersion() {
		assertEquals("test-image-tag", healthController.version().version());
	}

}
