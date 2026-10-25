package io.github.imzmq.interview.learning.application.contract;

import io.github.imzmq.interview.learning.application.contract.result.UserKnowledgeStateView;

import java.util.List;

public interface UserKnowledgeStateQuery {

    List<UserKnowledgeStateView> findByUserId(String userId);
}
