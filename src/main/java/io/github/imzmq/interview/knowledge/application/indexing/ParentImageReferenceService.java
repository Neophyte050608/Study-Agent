package io.github.imzmq.interview.knowledge.application.indexing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.media.application.contract.extension.ParentImageReferenceSupport;
import io.github.imzmq.interview.media.application.contract.extension.ParentImageReferenceSupport.ParentImageReferences;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class ParentImageReferenceService implements ParentImageReferenceSupport {

    private final RagParentMapper ragParentMapper;

    public ParentImageReferenceService(RagParentMapper ragParentMapper) {
        this.ragParentMapper = ragParentMapper;
    }

    @Override
    public List<ParentImageReferences> findByFilePaths(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }
        return ragParentMapper.selectList(
                        new LambdaQueryWrapper<RagParentDO>().in(RagParentDO::getFilePath, filePaths))
                .stream()
                .map(parent -> new ParentImageReferences(
                        parent.getParentId(),
                        parent.getFilePath(),
                        parent.getImageRefs()))
                .toList();
    }

    @Override
    public void replaceByParentIds(Map<String, List<String>> imageIdsByParentId) {
        if (imageIdsByParentId == null || imageIdsByParentId.isEmpty()) {
            return;
        }
        List<RagParentDO> parents = ragParentMapper.selectList(
                new LambdaQueryWrapper<RagParentDO>()
                        .in(RagParentDO::getParentId, imageIdsByParentId.keySet()));
        for (RagParentDO parent : parents) {
            parent.setImageRefs(new ArrayList<>(
                    imageIdsByParentId.getOrDefault(parent.getParentId(), List.of())));
            ragParentMapper.updateById(parent);
        }
    }
}
