package com.example.interview.service.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.dto.ModelCandidateDTO;
import com.example.interview.entity.ModelCandidateDO;
import com.example.interview.mapper.ModelCandidateMapper;
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
    public ModelCandidateDO create(ModelCandidateDTO dto) {
        ModelCandidateDO entity = new ModelCandidateDO();
        copyFromDto(entity, dto);
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt(dto.getApiKey()));
        }
        mapper.insert(entity);
        dynamicChatModelRegistry.evict(entity.getName());
        return mapper.selectById(entity.getId());
    }

    @CacheEvict(value = "modelCandidates", allEntries = true)
    public ModelCandidateDO update(Long id, ModelCandidateDTO dto) {
        ModelCandidateDO entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("候选模型不存在: " + id);
        }
        String oldName = entity.getName();
        copyFromDto(entity, dto);
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(apiKeyEncryptor.encrypt(dto.getApiKey()));
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

    public ModelCandidateDTO toMaskedDto(ModelCandidateDO entity) {
        ModelCandidateDTO dto = new ModelCandidateDTO();
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

    private void fillApiKeyView(ModelCandidateDTO dto, ModelCandidateDO entity) {
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

    private void copyFromDto(ModelCandidateDO entity, ModelCandidateDTO dto) {
        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }
        if (dto.getDisplayName() != null) {
            entity.setDisplayName(dto.getDisplayName());
        }
        if (dto.getProvider() != null) {
            entity.setProvider(dto.getProvider());
        }
        if (dto.getModel() != null) {
            entity.setModel(dto.getModel());
        }
        if (dto.getBaseUrl() != null) {
            entity.setBaseUrl(dto.getBaseUrl());
        }
        if (dto.getPriority() != null) {
            entity.setPriority(dto.getPriority());
        }
        if (dto.getIsPrimary() != null) {
            entity.setIsPrimary(dto.getIsPrimary());
        }
        if (dto.getSupportsThinking() != null) {
            entity.setSupportsThinking(dto.getSupportsThinking());
        }
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        if (dto.getRouteType() != null) {
            entity.setRouteType(dto.getRouteType());
        }
    }
}
