package io.github.imzmq.interview.modelrouting;

import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import org.junit.jupiter.api.Test;

import io.github.imzmq.interview.common.api.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyEncryptorTest {

    @Test
    void shouldRequireExplicitEncryptionKey() {
        BusinessException exception = assertThrows(BusinessException.class, () -> new ApiKeyEncryptor(""));

        assertEquals("缺少加密密钥，必须提供 32 字节 AES 密钥", exception.getMessage());
    }

    @Test
    void shouldRejectInsecureDefaultEncryptionKey() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new ApiKeyEncryptor("default-dev-key-32bytes!!"));

        assertEquals("禁止使用默认加密密钥", exception.getMessage());
    }

    @Test
    void shouldRejectWrongKeyLength() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new ApiKeyEncryptor("short-key"));

        assertEquals("加密密钥长度非法: 9", exception.getMessage());
    }

    @Test
    void shouldEncryptAndDecryptWithValid32ByteKey() {
        ApiKeyEncryptor encryptor = new ApiKeyEncryptor("12345678901234567890123456789012");

        String encrypted = encryptor.encrypt("sk-live-secret");

        assertEquals("sk-live-secret", encryptor.decrypt(encrypted));
    }
}


