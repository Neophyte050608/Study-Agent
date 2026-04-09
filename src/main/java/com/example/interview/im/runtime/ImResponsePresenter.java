package com.example.interview.im.runtime;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.core.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ImResponsePresenter {

    public String format(TaskResponse response) {
        Object data = response.data();
        if (data == null) {
            return response.message() != null ? response.message() : "任务执行完毕。";
        }

        StringBuilder sb = new StringBuilder();
        if (data instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }
        if (data instanceof InterviewOrchestratorAgent.AnswerResult result) {
            sb.append("【评分】").append(result.score()).append("\n\n");
            sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
            if (result.finished()) {
                sb.append("恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");
            } else {
                sb.append("【下一题】\n").append(result.nextQuestion());
            }
            return sb.toString();
        }
        if (data instanceof InterviewOrchestratorAgent.FinalReport report) {
            sb.append("====== 面试复盘报告 ======\n\n");
            sb.append("【总评】\n").append(report.summary()).append("\n\n");
            sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
            sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
            sb.append("【平均分】").append(String.format("%.1f", report.averageScore())).append("\n\n");
            sb.append("【后续建议】\n").append(report.nextFocus());
            return sb.toString();
        }
        if (data instanceof Map<?, ?> map) {
            if (map.containsKey("answer")) {
                return String.valueOf(map.get("answer"));
            }
            if (map.containsKey("question")) {
                return String.valueOf(map.get("question"));
            }
            if (map.containsKey("recommendation")) {
                return String.valueOf(map.get("recommendation"));
            }
            if (map.containsKey("summary")) {
                return String.valueOf(map.get("summary"));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (List.of("agent", "sessionId", "userId", "status", "traceId", "profileSnapshotApplied").contains(key)) {
                    continue;
                }
                sb.append("• ").append(key).append(": ").append(entry.getValue()).append("\n");
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? "处理成功。" : result;
        }
        return String.valueOf(data);
    }
}
