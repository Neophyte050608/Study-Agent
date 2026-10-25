package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.interview.application.contract.result.InterviewAnswerResult;
import io.github.imzmq.interview.interview.application.contract.result.InterviewFinalReport;

import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.interview.application.contract.result.InterviewSessionStarted;
import io.github.imzmq.interview.interview.application.chat.InterviewTaskResultPresenter;
import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.PresentationChannel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponsePresentationServiceTest {

    private final TaskResponsePresentationService service = new TaskResponsePresentationService(List.of(new InterviewTaskResultPresenter()));

    @Test
    void preservesDefaultErrorAndGenericMapMessages() {
        assertThat(service.format(null, PresentationChannel.WEB)).isEqualTo("处理完毕。");
        assertThat(service.format(null, PresentationChannel.IM)).isEqualTo("任务执行完毕。");
        assertThat(service.format(TaskResponse.fail("失败原因"), PresentationChannel.WEB))
                .isEqualTo("抱歉，处理您的请求时遇到了问题：失败原因");

        Map<String, Object> generic = new LinkedHashMap<>();
        generic.put("agent", "ignored");
        generic.put("name", "张三");
        generic.put("count", 2);
        assertThat(service.format(TaskResponse.ok(generic), PresentationChannel.WEB))
                .isEqualTo("• name: 张三\n• count: 2");
    }

    @Test
    void preservesInterviewSessionAnswerAndReportMessages() {
        InterviewSessionStarted session = new InterviewSessionStarted("s-1", "请解释 JVM 内存模型", 5);
        assertThat(service.format(TaskResponse.ok(session), PresentationChannel.WEB))
                .isEqualTo("请解释 JVM 内存模型");

        InterviewAnswerResult answer = new InterviewAnswerResult(
                85, "基础扎实", "下一题", 85.0, true, 1, 1,
                "MEDIUM", "DONE", 80.0, 80, 90, 85, 75, "", List.of(), List.of());
        assertThat(service.format(TaskResponse.ok(answer), PresentationChannel.WEB))
                .isEqualTo("【评分】85\n\n【反馈】\n基础扎实\n\n本次模拟面试已结束。你可以说\"生成报告\"查看最终评估。");
        assertThat(service.format(TaskResponse.ok(answer), PresentationChannel.IM))
                .isEqualTo("【评分】85\n\n【反馈】\n基础扎实\n\n恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");

        InterviewFinalReport report = new InterviewFinalReport(
                "整体良好", "", "并发", "边界遗漏", "", "继续刷题", 86.25, 3);
        assertThat(service.format(TaskResponse.ok(report), PresentationChannel.WEB))
                .isEqualTo("====== 面试复盘报告 ======\n\n【总评】\n整体良好\n\n【薄弱环节】\n并发\n\n【错误点】\n边界遗漏\n\n【后续建议】\n继续刷题");
        assertThat(service.format(TaskResponse.ok(report), PresentationChannel.IM))
                .contains("【平均分】86.3");
    }
}
