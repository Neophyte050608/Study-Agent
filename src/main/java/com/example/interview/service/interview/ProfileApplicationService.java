package com.example.interview.service.interview;

import com.example.interview.service.LearningProfileAgent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ProfileApplicationService {

    private final LearningProfileAgent learningProfileAgent;

    public ProfileApplicationService(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    public LearningProfileAgent.TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
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
