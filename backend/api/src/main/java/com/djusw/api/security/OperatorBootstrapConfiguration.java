package com.djusw.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OperatorBootstrapConfiguration {
    @Bean
    ApplicationRunner operatorBootstrap(OperatorBootstrapService bootstrap,
            @Value("${OPERATOR_BOOTSTRAP_USERNAME:}") String username,
            @Value("${OPERATOR_BOOTSTRAP_PASSWORD:}") String password) {
        return args -> {
            if (username.isBlank() && password.isBlank()) return;
            bootstrap.initialize(username, password);
        };
    }
}
