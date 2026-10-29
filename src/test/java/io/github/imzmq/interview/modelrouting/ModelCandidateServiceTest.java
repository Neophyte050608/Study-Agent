package io.github.imzmq.interview.modelrouting;

import io.github.imzmq.interview.modelrouting.api.ModelCandidateResponse;
import io.github.imzmq.interview.modelrouting.api.ModelCandidateWriteRequest;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateMapper;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCandidateServiceTest {

    @Test
    void shouldDegradeGracefullyWhenApiKeyCannotBeDecrypted() {
        ModelCandidateMapper mapper = mock(ModelCandidateMapper.class);
        ApiKeyEncryptor apiKeyEncryptor = new ApiKeyEncryptor("12345678901234567890123456789012");
        DynamicChatModelRegistry dynamicChatModelRegistry = mock(DynamicChatModelRegistry.class);
        ModelCandidateService service = new ModelCandidateService(mapper, apiKeyEncryptor, dynamicChatModelRegistry);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(1L);
        entity.setName("broken-key-model");
        entity.setApiKeyEncrypted("not-a-valid-cipher");

        ModelCandidateResponse dto = service.toResponse(entity);

        assertTrue(dto.getApiKeyConfigured());
        assertFalse(dto.getApiKeyReadable());
        assertEquals("[解密失败]", dto.getApiKeyMasked());
    }

    @Test
    void shouldMarkReadableKeyWhenDecryptionSucceeds() {
        ModelCandidateMapper mapper = mock(ModelCandidateMapper.class);
        ApiKeyEncryptor apiKeyEncryptor = new ApiKeyEncryptor("12345678901234567890123456789012");
        DynamicChatModelRegistry dynamicChatModelRegistry = mock(DynamicChatModelRegistry.class);
        ModelCandidateService service = new ModelCandidateService(mapper, apiKeyEncryptor, dynamicChatModelRegistry);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(2L);
        entity.setName("healthy-key-model");
        entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt("sk-live-secret"));

        ModelCandidateResponse dto = service.toResponse(entity);

        assertTrue(dto.getApiKeyConfigured());
        assertTrue(dto.getApiKeyReadable());
        assertEquals("sk-l****cret", dto.getApiKeyMasked());
    }

    @Test
    void blankKeyPreservesExistingSecretAndExplicitClearRemovesIt() {
        ModelCandidateMapper mapper = mock(ModelCandidateMapper.class);
        ApiKeyEncryptor apiKeyEncryptor = new ApiKeyEncryptor("12345678901234567890123456789012");
        DynamicChatModelRegistry registry = mock(DynamicChatModelRegistry.class);
        ModelCandidateService service = new ModelCandidateService(mapper, apiKeyEncryptor, registry);
        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(7L);
        entity.setName("safe-model");
        entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt("existing-secret"));
        when(mapper.selectById(7L)).thenReturn(entity);
        when(mapper.updateById(any(ModelCandidateDO.class))).thenReturn(1);

        ModelCandidateWriteRequest preserve = new ModelCandidateWriteRequest();
        preserve.setApiKey("  ");
        service.update(7L, preserve);
        assertEquals("existing-secret", apiKeyEncryptor.decrypt(entity.getApiKeyEncrypted()));

        ModelCandidateWriteRequest clear = new ModelCandidateWriteRequest();
        clear.setClearApiKey(true);
        service.update(7L, clear);
        assertNull(entity.getApiKeyEncrypted());
        verify(mapper, org.mockito.Mockito.times(2)).updateById(entity);
    }

}
