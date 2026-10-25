package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.result.TopicCapabilityCurve;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ProfileApplicationService {

    private final LearningProfileOperations learningProfileAgent;

    public ProfileApplicationService(LearningProfileOperations learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    public TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        return learningProfileAgent.getTopicCapabilityCurve(userId, topic);
    }

    public Map<String, Object> getProfileOverview(String userId) {
        return learningProfileAgent.overview(userId);
    }

    public String getProfileRecommendation(String userId, String mode) {
        return learningProfileAgent.recommend(userId, mode);
    }

    public List<Map<String, Object>> getProfileEvents(String userId, int limit) {
        return learningProfileAgent.listEvents(userId, limit);
    }
}


