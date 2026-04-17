package io.github.imzmq.interview.modelrouting.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ApiKeyEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String INSECURE_DEFAULT_KEY = "default-dev-key-32bytes!!";

    private final SecretKeySpec keySpec;

    public ApiKeyEncryptor(@Value("${app.security.encryption-key:}") String key) {
        this.keySpec = new SecretKeySpec(normalizeKey(key), "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文格式非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("API Key 解密失败", ex);
        }
    }

    public String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        if (plainKey.length() <= 8) {
            String prefix = plainKey.substring(0, Math.min(2, plainKey.length()));
            String suffix = plainKey.length() > 2 ? plainKey.substring(Math.max(plainKey.length() - 2, 0)) : "";
            return prefix + "****" + suffix;
        }
        return plainKey.substring(0, 4) + "****" + plainKey.substring(plainKey.length() - 4);
    }

    private byte[] normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("缺少 app.security.encryption-key，必须提供 32 字节 AES 密钥");
        }
        if (INSECURE_DEFAULT_KEY.equals(key)) {
            throw new IllegalStateException("禁止使用默认加密密钥，请设置独立的 app.security.encryption-key");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("app.security.encryption-key 必须严格为 32 字节，当前为 " + keyBytes.length + " 字节");
        }
        return keyBytes;
    }
}



