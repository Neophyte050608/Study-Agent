package io.github.imzmq.interview.learning.application.contract.result;

import java.util.List;

public record TopicCapabilityCurve(
        String topic,
        List<String> timestamps,
        List<Double> scores,
        double averageScore
) {
}
