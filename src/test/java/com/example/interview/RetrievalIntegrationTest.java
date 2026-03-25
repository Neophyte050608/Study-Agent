package com.example.interview;

import com.example.interview.service.RetrievalEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 检索效果真实数据评测集成测试。
 * 用于获取项目真实的 Recall@K 和 MRR 数据。
 */
@SpringBootTest(properties = {
        "rocketmq.name-server=127.0.0.1:9876",
        "app.a2a.bus.type=inmemory",
        "app.observability.retrieval-eval-enabled=true"
})
public class RetrievalIntegrationTest {

    @Autowired
    private RetrievalEvaluationService retrievalEvaluationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void runRealEvaluation() throws Exception {
        System.out.println(">>> 开始运行真实检索效果评测...");
        RetrievalEvaluationService.RetrievalEvalReport report = retrievalEvaluationService.runDefaultEval();
        
        System.out.println("====================================================");
        System.out.println("真实评测报告 (Real Retrieval Evaluation Report)");
        System.out.println("====================================================");
        System.out.println("评测时间: " + report.timestamp());
        System.out.println("总用例数: " + report.totalCases());
        System.out.println("召回命中数 (Top-5): " + report.hitCases());
        System.out.println("Recall@1: " + String.format("%.2f%%", report.recallAt1() * 100));
        System.out.println("Recall@3: " + String.format("%.2f%%", report.recallAt3() * 100));
        System.out.println("Recall@5: " + String.format("%.2f%%", report.recallAt5() * 100));
        System.out.println("MRR: " + String.format("%.4f", report.mrr()));
        System.out.println("====================================================");
        
        // 打印详细结果摘要
        report.results().forEach(res -> {
            System.out.println(String.format("Query: %s | Hit: %b | Rank: %d", 
                res.query(), res.hit(), res.rank()));
        });
        System.out.println("====================================================");
    }
}
