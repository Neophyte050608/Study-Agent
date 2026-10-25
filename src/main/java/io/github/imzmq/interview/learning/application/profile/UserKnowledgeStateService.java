package io.github.imzmq.interview.learning.application.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.learning.application.contract.UserKnowledgeStateQuery;
import io.github.imzmq.interview.learning.application.contract.result.UserKnowledgeStateView;
import io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateDO;
import io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserKnowledgeStateService implements UserKnowledgeStateQuery {

    private final UserKnowledgeStateMapper knowledgeStateMapper;

    public UserKnowledgeStateService(UserKnowledgeStateMapper knowledgeStateMapper) {
        this.knowledgeStateMapper = knowledgeStateMapper;
    }

    @Override
    public List<UserKnowledgeStateView> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return knowledgeStateMapper.selectList(new LambdaQueryWrapper<UserKnowledgeStateDO>()
                        .eq(UserKnowledgeStateDO::getUserId, userId))
                .stream()
                .map(state -> new UserKnowledgeStateView(state.getTopic(), state.getMasteryScore()))
                .toList();
    }
}
