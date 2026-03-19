package com.example.interview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.interview.service.RAGService;

import java.lang.reflect.Method;

@SpringBootTest
public class EvaluationTest {

    @Autowired
    private RAGService ragService;

    @Test
    public void testGenerateEvaluation() throws Exception {
        Method method = RAGService.class.getDeclaredMethod("generateEvaluation", 
            String.class, String.class, String.class, String.class, String.class, 
            double.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        
        try {
            String result = (String) method.invoke(ragService, 
                "Java HashMap", 
                "HashMap的工作原理是什么？", 
                "就是一个数组加链表，通过hash计算位置。", 
                "BASIC", 
                "PROBE", 
                65.0, 
                "了解基础概念", 
                "这里是上下文信息，关于HashMap的工作原理。...", 
                "1. [web] HashMap原理", 
                "");
            System.out.println("Result: " + result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
