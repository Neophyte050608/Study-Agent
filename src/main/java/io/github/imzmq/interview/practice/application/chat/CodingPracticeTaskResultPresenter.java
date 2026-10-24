package io.github.imzmq.interview.practice.application.chat;

import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.PresentationChannel;
import io.github.imzmq.interview.chat.application.contract.extension.ChatTaskResultPresenter;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CodingPracticeTaskResultPresenter implements ChatTaskResultPresenter {

    @Override
    public boolean supports(Object result) {
        return result instanceof Map<?, ?> map
                && "CodingPracticeAgent".equals(map.get("agent"));
    }

    @Override
    public String format(Object result, PresentationChannel channel) {
        if (!(result instanceof Map<?, ?> map) || !supports(result)) {
            throw new IllegalArgumentException("Unsupported coding practice result: " + result);
        }

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
        String formatted = sb.toString().trim();
        return formatted.isEmpty() ? "处理完毕。" : formatted;
    }
}
