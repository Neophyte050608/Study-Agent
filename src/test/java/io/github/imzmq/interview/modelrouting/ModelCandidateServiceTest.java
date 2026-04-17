package io.github.imzmq.interview.modelrouting;

import io.github.imzmq.interview.dto.modelrouting.ModelCandidateDTO;
import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.mapper.modelrouting.ModelCandidateMapper;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

        ModelCandidateDTO dto = service.toMaskedDto(entity);

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

        ModelCandidateDTO dto = service.toMaskedDto(entity);

        assertTrue(dto.getApiKeyConfigured());
        assertTrue(dto.getApiKeyReadable());
        assertEquals("sk-l****cret", dto.getApiKeyMasked());
    }
}




