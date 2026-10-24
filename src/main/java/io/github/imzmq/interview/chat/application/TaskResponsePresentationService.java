package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatTaskResultPresenter;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TaskResponsePresentationService implements TaskResponsePresentationOperations {

    private final List<ChatTaskResultPresenter> resultPresenters;

    public TaskResponsePresentationService(List<ChatTaskResultPresenter> resultPresenters) {
        List<ChatTaskResultPresenter> ordered = new ArrayList<>(resultPresenters);
        AnnotationAwareOrderComparator.sort(ordered);
        this.resultPresenters = List.copyOf(ordered);
    }

    @Override
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
        for (ChatTaskResultPresenter presenter : resultPresenters) {
            if (presenter.supports(data)) {
                return presenter.format(data, channel);
            }
        }
        if (data instanceof Map<?, ?> map) {
            if (map.containsKey("answer")) return String.valueOf(map.get("answer"));
            if (map.containsKey("question")) return String.valueOf(map.get("question"));
            if (map.containsKey("recommendation")) return String.valueOf(map.get("recommendation"));
            if (map.containsKey("summary")) return String.valueOf(map.get("summary"));
            return formatGenericMap(map);
        }
        return String.valueOf(data);
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
