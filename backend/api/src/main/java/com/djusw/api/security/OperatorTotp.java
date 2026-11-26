package com.djusw.api.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
class OperatorTotp {
    /** 일치하는 30초 단계. 시계 차이를 위해 현재 단계와 앞뒤 한 단계를 허용한다. */
    Long matchingStep(String secret, String code, Instant now, Long lastStep) {
        if (code == null || !code.matches("[0-9]{6}")) return null;
        byte[] key = decodeBase32(secret);
        long current = Math.floorDiv(now.getEpochSecond(), 30);
        for (long step : new long[] {current, current - 1, current + 1}) {
            if (step < 0 || (lastStep != null && step <= lastStep)) continue;
            String expected = code(key, step);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return null;
    }

    private static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OTP를 검증하지 못했습니다.");
        }
    }

    private static byte[] decodeBase32(String secret) {
        if (secret == null || !secret.matches("[A-Z2-7]{32}")) throw new IllegalStateException("OTP 비밀키 형식이 잘못되었습니다.");
        int buffer = 0, bits = 0;
        var decoded = new ByteArrayOutputStream();
        for (char c : secret.toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c - '2' + 26;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                decoded.write((buffer >>> bits) & 0xff);
            }
        }
        return decoded.toByteArray();
    }
}
