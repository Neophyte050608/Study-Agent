package io.github.imzmq.interview.modelrouting.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.modelrouting.api.ModelCandidateResponse;
import io.github.imzmq.interview.modelrouting.api.ModelCandidateWriteRequest;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateMapper;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelCandidateService {

    private static final Logger logger = LoggerFactory.getLogger(ModelCandidateService.class);

    private final ModelCandidateMapper mapper;
    private final ApiKeyEncryptor apiKeyEncryptor;
    private final DynamicChatModelRegistry dynamicChatModelRegistry;

    public ModelCandidateService(ModelCandidateMapper mapper,
                                 ApiKeyEncryptor apiKeyEncryptor,
                                 DynamicChatModelRegistry dynamicChatModelRegistry) {
        this.mapper = mapper;
        this.apiKeyEncryptor = apiKeyEncryptor;
        this.dynamicChatModelRegistry = dynamicChatModelRegistry;
    }

    @Cacheable(value = "modelCandidates", key = "'all'")
    public List<ModelCandidateDO> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ModelCandidateDO>()
                .orderByAsc(ModelCandidateDO::getPriority, ModelCandidateDO::getId));
    }

    public List<ModelCandidateDO> listEnabled() {
        return listAll().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
                .toList();
    }

    public ModelCandidateDO getById(Long id) {
        return mapper.selectById(id);
    }

    @CacheEvict(value = "modelCandidates", allEntries = true)
    public ModelCandidateDO create(ModelCandidateWriteRequest request) {
        ModelCandidateDO entity = new ModelCandidateDO();
        copyFromRequest(entity, request);
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt(request.getApiKey()));
        }
        mapper.insert(entity);
        dynamicChatModelRegistry.evict(entity.getName());
        return mapper.selectById(entity.getId());
    }

    @CacheEvict(value = "modelCandidates", allEntries = true)
    public ModelCandidateDO update(Long id, ModelCandidateWriteRequest request) {
        ModelCandidateDO entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("候选模型不存在: " + id);
        }
        String oldName = entity.getName();
        copyFromRequest(entity, request);
        if (request.isClearApiKey()) {
            entity.setApiKeyEncrypted(null);
        } else if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt(request.getApiKey()));
        }
        mapper.updateById(entity);
        dynamicChatModelRegistry.evict(oldName);
        if (entity.getName() != null && !entity.getName().equals(oldName)) {
            dynamicChatModelRegistry.evict(entity.getName());
        }
        return mapper.selectById(id);
    }

    @CacheEvict(value = "modelCandidates", allEntries = true)
    public void delete(Long id) {
        ModelCandidateDO entity = mapper.selectById(id);
        if (entity != null) {
            mapper.deleteById(id);
            dynamicChatModelRegistry.evict(entity.getName());
        }
    }

    @CacheEvict(value = "modelCandidates", allEntries = true)
    public void toggleEnabled(Long id) {
        ModelCandidateDO entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("候选模型不存在: " + id);
        }
        entity.setEnabled(!Boolean.TRUE.equals(entity.getEnabled()));
        mapper.updateById(entity);
        dynamicChatModelRegistry.evict(entity.getName());
    }

    public String decryptApiKey(Long id) {
        ModelCandidateDO entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("候选模型不存在: " + id);
        }
        return apiKeyEncryptor.decrypt(entity.getApiKeyEncrypted());
    }

    public ModelCandidateResponse toResponse(ModelCandidateDO entity) {
        ModelCandidateResponse dto = new ModelCandidateResponse();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDisplayName(entity.getDisplayName());
        dto.setProvider(entity.getProvider());
        dto.setModel(entity.getModel());
        dto.setBaseUrl(entity.getBaseUrl());
        fillApiKeyView(dto, entity);
        dto.setPriority(entity.getPriority());
        dto.setIsPrimary(entity.getIsPrimary());
        dto.setSupportsThinking(entity.getSupportsThinking());
        dto.setEnabled(entity.getEnabled());
        dto.setRouteType(entity.getRouteType());
        return dto;
    }

    private void fillApiKeyView(ModelCandidateResponse dto, ModelCandidateDO entity) {
        String encrypted = entity.getApiKeyEncrypted();
        boolean configured = encrypted != null && !encrypted.isBlank();
        dto.setApiKeyConfigured(configured);
        if (!configured) {
            dto.setApiKeyReadable(false);
            dto.setApiKeyMasked("");
            return;
        }
        try {
            String plainKey = apiKeyEncryptor.decrypt(encrypted);
            dto.setApiKeyReadable(true);
            dto.setApiKeyMasked(apiKeyEncryptor.mask(plainKey));
        } catch (RuntimeException ex) {
            dto.setApiKeyReadable(false);
            dto.setApiKeyMasked("[解密失败]");
            logger.warn("模型候选密钥解密失败: id={}, name={}", entity.getId(), entity.getName());
        }
    }

    private void copyFromRequest(ModelCandidateDO entity, ModelCandidateWriteRequest request) {
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getDisplayName() != null) {
            entity.setDisplayName(request.getDisplayName());
        }
        if (request.getProvider() != null) {
            entity.setProvider(request.getProvider());
        }
        if (request.getModel() != null) {
            entity.setModel(request.getModel());
        }
        if (request.getBaseUrl() != null) {
            entity.setBaseUrl(request.getBaseUrl());
        }
        if (request.getPriority() != null) {
            entity.setPriority(request.getPriority());
        }
        if (request.getIsPrimary() != null) {
            entity.setIsPrimary(request.getIsPrimary());
        }
        if (request.getSupportsThinking() != null) {
            entity.setSupportsThinking(request.getSupportsThinking());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        if (request.getRouteType() != null) {
            entity.setRouteType(request.getRouteType());
        }
    }
}





