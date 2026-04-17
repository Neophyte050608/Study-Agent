package io.github.imzmq.interview.knowledge.domain;

public record TopicState(
        String topicId,
        String knowledgeDigest,
        int turnCount,
        long lastActiveTurnId
) {
    public TopicState withIncrementedTurn(long turnId) {
        return new TopicState(topicId, knowledgeDigest, turnCount + 1, turnId);
    }

    public TopicState withDigest(String digest) {
        return new TopicState(topicId, digest, turnCount, lastActiveTurnId);
    }
}


