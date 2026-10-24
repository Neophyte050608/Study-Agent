package io.github.imzmq.interview.practice.application.chat;

import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.PresentationChannel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingPracticeTaskResultPresenterTest {

    private final CodingPracticeTaskResultPresenter presenter = new CodingPracticeTaskResultPresenter();

    @Test
    void supportsOnlyCodingPracticeAgentMaps() {
        assertThat(presenter.supports(Map.of("agent", "CodingPracticeAgent"))).isTrue();
        assertThat(presenter.supports(Map.of("agent", "OtherAgent"))).isFalse();
        assertThat(presenter.supports("CodingPracticeAgent")).isFalse();
    }

    @Test
    void preservesEvaluatedQuestionCompletedAndFallbackText() {
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("agent", "CodingPracticeAgent");
        evaluated.put("status", "evaluated");
        evaluated.put("score", 80);
        evaluated.put("feedback", "不错");
        evaluated.put("nextHint", "继续优化");
        evaluated.put("progress", "1/3");
        assertThat(presenter.format(evaluated, PresentationChannel.WEB))
                .isEqualTo("【得分】80\n\n【解析与反馈】\n不错\n\n【提示】继续优化\n\n进度：1/3");

        assertThat(presenter.format(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "question_generated",
                "question", "解释 volatile",
                "progress", "2/3"
        ), PresentationChannel.IM)).isEqualTo("解释 volatile\n\n(进度：2/3)");

        assertThat(presenter.format(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "completed",
                "message", "本轮结束"
        ), PresentationChannel.WEB)).isEqualTo("本轮结束");

        assertThat(presenter.format(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "unknown",
                "question", "下一题"
        ), PresentationChannel.WEB)).isEqualTo("下一题");
    }

    @Test
    void rejectsUnsupportedResults() {
        assertThatThrownBy(() -> presenter.format(Map.of("agent", "OtherAgent"), PresentationChannel.WEB))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
