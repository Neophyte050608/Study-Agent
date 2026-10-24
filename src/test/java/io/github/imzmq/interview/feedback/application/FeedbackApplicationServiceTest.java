package io.github.imzmq.interview.feedback.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.imzmq.interview.feedback.application.contract.result.FeedbackMetrics;
import io.github.imzmq.interview.feedback.infrastructure.persistence.RagFeedbackDO;
import io.github.imzmq.interview.feedback.infrastructure.persistence.RagFeedbackMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackApplicationServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "feedback-test");
        TableInfoHelper.initTableInfo(assistant, RagFeedbackDO.class);
    }

    @Test
    void summarizeCountsSupportedFeedbackTypesWithinTheRequestedWindow() {
        RagFeedbackMapper mapper = mock(RagFeedbackMapper.class);
        when(mapper.selectCount(any())).thenReturn(7L, 2L, 5L);
        FeedbackApplicationService service = new FeedbackApplicationService(mapper);
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 10, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 24, 11, 0);

        FeedbackMetrics metrics = service.summarize(from, to);

        assertThat(metrics).isEqualTo(new FeedbackMetrics(7L, 2L, 5L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<RagFeedbackDO>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper, times(3)).selectCount(wrapperCaptor.capture());
        List<LambdaQueryWrapper<RagFeedbackDO>> wrappers = wrapperCaptor.getAllValues();
        assertThat(wrappers).allSatisfy(wrapper -> {
            assertThat(wrapper.getSqlSegment()).contains("created_at >=").contains("created_at <");
            assertThat(wrapper.getParamNameValuePairs()).containsValue(from).containsValue(to);
        });
        assertThat(wrappers)
                .extracting(LambdaQueryWrapper::getParamNameValuePairs)
                .anySatisfy(values -> assertThat(values).containsValue("THUMBS_UP"))
                .anySatisfy(values -> assertThat(values).containsValue("THUMBS_DOWN"))
                .anySatisfy(values -> assertThat(values).containsValue("COPY"));
    }

    @Test
    void summarizeReturnsZeroCountsWhenNoFeedbackExists() {
        RagFeedbackMapper mapper = mock(RagFeedbackMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        FeedbackApplicationService service = new FeedbackApplicationService(mapper);

        FeedbackMetrics metrics = service.summarize(
                LocalDateTime.of(2026, 10, 24, 10, 0),
                LocalDateTime.of(2026, 10, 24, 11, 0));

        assertThat(metrics).isEqualTo(new FeedbackMetrics(0L, 0L, 0L));
    }
}
