package com.djusw.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Component;

/** 계정 계약의 scrypt$log2N$r$p$salt$hash 형식을 사용한다. */
@Component
final class OperatorPasswords {
    private final SecureRandom random = new SecureRandom();

    String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 1 << 14, 8, 1, 32);
        return "scrypt$14$8$1$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    boolean matches(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 6 || !parts[0].equals("scrypt")) return false;
            int logN = Integer.parseInt(parts[1]);
            int r = Integer.parseInt(parts[2]);
            int p = Integer.parseInt(parts[3]);
            // 저장된 값이 손상되어 과도한 메모리를 할당하지 않도록 제한한다.
            if (logN < 14 || logN > 18 || r < 1 || r > 8 || p < 1 || p > 4) return false;
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            if (salt.length < 16 || salt.length > 64 || expected.length != 32) return false;
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 1 << logN, r, p, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }
}
