package com.example.interview;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

public class PromptTemplateTest {
    @Test
    public void test() {
        PromptTemplate template = new PromptTemplate("User answer: {answer}");
        String result = template.render(Map.of("answer", "This is a {test}"));
        System.out.println("Result: " + result);
    }
}
