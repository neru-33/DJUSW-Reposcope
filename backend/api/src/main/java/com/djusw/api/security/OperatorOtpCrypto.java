package com.djusw.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class OperatorOtpCrypto {
    private static final String PREFIX = "v1:";
    private final SecureRandom random = new SecureRandom();
    private final String configuredKey;

    OperatorOtpCrypto(@Value("${app.operator.otp-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    String encrypt(long userId, String secret) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, userId, nonce);
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OTP 비밀키를 암호화하지 못했습니다.");
        }
    }

    String decrypt(long userId, String value) {
        try {
            if (value == null || !value.startsWith(PREFIX)) throw new IllegalArgumentException();
            byte[] combined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (combined.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            return new String(cipher(Cipher.DECRYPT_MODE, userId, nonce).doFinal(ciphertext), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("OTP 비밀키를 복호화하지 못했습니다.");
        }
    }

    private Cipher cipher(int mode, long userId, byte[] nonce) throws GeneralSecurityException {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("OPERATOR_OTP_KEY 설정을 확인하세요.");
        }
        if (key.length != 32) throw new IllegalStateException("OPERATOR_OTP_KEY는 Base64로 인코딩한 32바이트 키여야 합니다.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.US_ASCII));
        java.util.Arrays.fill(key, (byte) 0);
        return cipher;
    }
}
