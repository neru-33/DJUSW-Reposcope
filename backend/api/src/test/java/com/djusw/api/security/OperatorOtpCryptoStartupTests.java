package com.djusw.api.security;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class OperatorOtpCryptoStartupTests {
    @Test
    void invalidConfiguredKeyPreventsContextStartup() {
        for (String key : new String[]{"not-base64!", Base64.getEncoder().encodeToString(new byte[31])}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(OperatorOtpCrypto.class, () -> new OperatorOtpCrypto(key));
                var error = assertThrows(BeanCreationException.class, context::refresh);
                assertInstanceOf(IllegalStateException.class, error.getCause());
            }
        }
    }

    @Test
    void validAndUnsetKeysAllowContextStartup() {
        for (String key : new String[]{"", Base64.getEncoder().encodeToString(new byte[32])}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(OperatorOtpCrypto.class, () -> new OperatorOtpCrypto(key));
                context.refresh();
                assertNotNull(context.getBean(OperatorOtpCrypto.class));
            }
        }
    }
}
