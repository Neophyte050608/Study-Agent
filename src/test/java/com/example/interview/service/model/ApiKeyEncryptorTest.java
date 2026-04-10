package com.example.interview.service.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyEncryptorTest {

    @Test
    void shouldRequireExplicitEncryptionKey() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new ApiKeyEncryptor(""));

        assertEquals("缺少 app.security.encryption-key，必须提供 32 字节 AES 密钥", exception.getMessage());
    }

    @Test
    void shouldRejectInsecureDefaultEncryptionKey() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ApiKeyEncryptor("default-dev-key-32bytes!!"));

        assertEquals("禁止使用默认加密密钥，请设置独立的 app.security.encryption-key", exception.getMessage());
    }

    @Test
    void shouldRejectWrongKeyLength() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ApiKeyEncryptor("short-key"));

        assertEquals("app.security.encryption-key 必须严格为 32 字节，当前为 9 字节", exception.getMessage());
    }

    @Test
    void shouldEncryptAndDecryptWithValid32ByteKey() {
        ApiKeyEncryptor encryptor = new ApiKeyEncryptor("12345678901234567890123456789012");

        String encrypted = encryptor.encrypt("sk-live-secret");

        assertEquals("sk-live-secret", encryptor.decrypt(encrypted));
    }
}
