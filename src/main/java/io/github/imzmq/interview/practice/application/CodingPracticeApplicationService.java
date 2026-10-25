package io.github.imzmq.interview.practice.application;

import io.github.imzmq.interview.practice.application.agent.CodingPracticeAgent;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Application entry point for the coding-practice HTTP use cases.
 */
@Service
public class CodingPracticeApplicationService {

    private final CodingPracticeAgent codingPracticeAgent;

    public CodingPracticeApplicationService(CodingPracticeAgent codingPracticeAgent) {
        this.codingPracticeAgent = codingPracticeAgent;
    }

    public Map<String, Object> execute(Map<String, Object> input) {
        return codingPracticeAgent.execute(input);
    }
}
