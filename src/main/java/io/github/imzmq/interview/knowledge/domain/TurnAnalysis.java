package io.github.imzmq.interview.knowledge.domain;

public record TurnAnalysis(
        boolean topicSwitch,
        DialogAct dialogAct,
        double infoNovelty,
        String currentTopic,
        String previousTopic
) {
    public static TurnAnalysis defaultContinuation(String topic) {
        return new TurnAnalysis(false, DialogAct.FOLLOW_UP, 0.3, topic, topic);
    }

    public static TurnAnalysis firstTurn(String topic) {
        return new TurnAnalysis(false, DialogAct.NEW_QUESTION, 1.0, topic, "");
    }
}


