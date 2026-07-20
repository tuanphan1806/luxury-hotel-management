package com.hotel.backend.service;

import com.hotel.backend.config.RefundConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class RefundDataCipher {

    public static final String KEY_VERSION = "v1";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefundDataCipher(RefundConfig config) {
        String encoded = config.getDataEncryptionKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("REFUND_DATA_ENCRYPTION_KEY chưa được cấu hình");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("REFUND_DATA_ENCRYPTION_KEY phải là Base64 hợp lệ", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("REFUND_DATA_ENCRYPTION_KEY phải giải mã thành đúng 32 byte");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Dữ liệu hoàn tiền không được để trống");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return KEY_VERSION + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không thể mã hóa dữ liệu hoàn tiền", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(KEY_VERSION + ":")) {
            throw new IllegalStateException("Dữ liệu hoàn tiền không có phiên bản mã hóa hợp lệ");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(KEY_VERSION.length() + 1));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalStateException("Dữ liệu hoàn tiền đã mã hóa không hợp lệ");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Không thể giải mã dữ liệu hoàn tiền", exception);
        }
    }

    public static String maskAccountNumber(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        String last4 = digits.substring(Math.max(0, digits.length() - 4));
        return "****" + last4;
    }

    public static String maskPersonName(String value) {
        if (value == null || value.isBlank()) return null;
        return java.util.Arrays.stream(value.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1) + "***")
                .reduce((left, right) -> left + " " + right)
                .orElse("***");
    }
}
