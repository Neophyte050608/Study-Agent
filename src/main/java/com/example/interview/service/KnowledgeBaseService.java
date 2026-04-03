package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.KnowledgeBaseDO;
import com.example.interview.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    public List<KnowledgeBaseDO> listAll() {
        List<KnowledgeBaseDO> list = knowledgeBaseMapper.selectList(new LambdaQueryWrapper<>());
        if (list == null || list.isEmpty()) {
            KnowledgeBaseDO def = new KnowledgeBaseDO();
            def.setName("默认知识库");
            def.setStatus("ACTIVE");
            def.setSourceType("LOCAL_VAULT");
            knowledgeBaseMapper.insert(def);
            list = knowledgeBaseMapper.selectList(new LambdaQueryWrapper<>());
        }
        return list;
    }
}
