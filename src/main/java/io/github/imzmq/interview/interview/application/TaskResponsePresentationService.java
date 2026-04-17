package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TaskResponsePresentationService {

    public enum PresentationChannel {
        WEB,
        IM
    }

    public String format(TaskResponse response, PresentationChannel channel) {
        if (response == null) {
            return defaultDoneMessage(channel);
        }
        if (!response.success()) {
            return "抱歉，处理您的请求时遇到了问题：" + response.message();
        }
        Object data = response.data();
        if (data == null) {
            return response.message() != null ? response.message() : defaultDoneMessage(channel);
        }
        if (data instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }
        if (data instanceof InterviewOrchestratorAgent.AnswerResult result) {
            return formatInterviewAnswer(result, channel);
        }
        if (data instanceof InterviewOrchestratorAgent.FinalReport report) {
            return formatFinalReport(report, channel);
        }
        if (data instanceof Map<?, ?> map) {
            if ("CodingPracticeAgent".equals(map.get("agent"))) {
                return formatCodingResult(map);
            }
            if (map.containsKey("answer")) return String.valueOf(map.get("answer"));
            if (map.containsKey("question")) return String.valueOf(map.get("question"));
            if (map.containsKey("recommendation")) return String.valueOf(map.get("recommendation"));
            if (map.containsKey("summary")) return String.valueOf(map.get("summary"));
            return formatGenericMap(map);
        }
        return String.valueOf(data);
    }

    private String formatInterviewAnswer(InterviewOrchestratorAgent.AnswerResult result, PresentationChannel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("【评分】").append(result.score()).append("\n\n");
        sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
        if (result.finished()) {
            if (channel == PresentationChannel.IM) {
                sb.append("恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");
            } else {
                sb.append("本次模拟面试已结束。你可以说\"生成报告\"查看最终评估。");
            }
        } else {
            sb.append("【下一题】\n").append(result.nextQuestion());
        }
        return sb.toString();
    }

    private String formatFinalReport(InterviewOrchestratorAgent.FinalReport report, PresentationChannel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("====== 面试复盘报告 ======\n\n");
        sb.append("【总评】\n").append(report.summary()).append("\n\n");
        sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
        sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
        if (channel == PresentationChannel.IM) {
            sb.append("【平均分】").append(String.format("%.1f", report.averageScore())).append("\n\n");
        }
        sb.append("【后续建议】\n").append(report.nextFocus());
        return sb.toString();
    }

    private String formatCodingResult(Map<?, ?> map) {
        Object statusObj = map.get("status");
        String status = statusObj == null ? "" : String.valueOf(statusObj);
        StringBuilder sb = new StringBuilder();

        if ("evaluated".equals(status)) {
            Object score = map.get("score");
            Object feedback = map.get("feedback");
            Object nextHint = map.get("nextHint");
            Object progress = map.get("progress");
            if (score != null) sb.append("【得分】").append(score).append("\n\n");
            if (feedback != null && !String.valueOf(feedback).isBlank()) {
                sb.append("【解析与反馈】\n").append(feedback).append("\n\n");
            }
            if (nextHint != null && !String.valueOf(nextHint).isBlank()) {
                sb.append("【提示】").append(nextHint).append("\n\n");
            }
            if (Boolean.TRUE.equals(map.get("isLast"))) {
                sb.append("本轮练习已完成！");
            } else if (progress != null) {
                sb.append("进度：").append(progress);
            }
        } else if ("question_generated".equals(status) || "started".equals(status)) {
            Object question = map.get("question");
            Object progress = map.get("progress");
            if (question != null) sb.append(question);
            if (progress != null) sb.append("\n\n(进度：").append(progress).append(")");
        } else if ("completed".equals(status)) {
            Object message = map.get("message");
            sb.append(message != null ? message : "练习已完成！");
        } else {
            Object question = map.get("question");
            Object message = map.get("message");
            if (question != null) return String.valueOf(question);
            if (message != null) return String.valueOf(message);
            return "处理完毕。";
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "处理完毕。" : result;
    }

    private String formatGenericMap(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
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

    private String defaultDoneMessage(PresentationChannel channel) {
        return channel == PresentationChannel.IM ? "任务执行完毕。" : "处理完毕。";
    }
}





