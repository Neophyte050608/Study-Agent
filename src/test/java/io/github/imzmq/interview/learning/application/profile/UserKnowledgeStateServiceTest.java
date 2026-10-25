package io.github.imzmq.interview.learning.application.profile;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.imzmq.interview.learning.application.contract.result.UserKnowledgeStateView;
import io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateDO;
import io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserKnowledgeStateServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "user-knowledge-state-service-test");
        TableInfoHelper.initTableInfo(assistant, UserKnowledgeStateDO.class);
    }

    @Test
    void findsStableKnowledgeStateViewsByUserId() {
        UserKnowledgeStateMapper mapper = mock(UserKnowledgeStateMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                state("Java", 0.25d),
                state("Redis", null)
        ));
        UserKnowledgeStateService service = new UserKnowledgeStateService(mapper);

        List<UserKnowledgeStateView> results = service.findByUserId("user-1");

        assertThat(results).containsExactly(
                new UserKnowledgeStateView("Java", 0.25d),
                new UserKnowledgeStateView("Redis", null)
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserKnowledgeStateDO>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("user_id");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values()).contains("user-1");
    }

    @Test
    void returnsEmptyWithoutPersistenceAccessForBlankUserId() {
        UserKnowledgeStateMapper mapper = mock(UserKnowledgeStateMapper.class);
        UserKnowledgeStateService service = new UserKnowledgeStateService(mapper);

        assertThat(service.findByUserId(null)).isEmpty();
        assertThat(service.findByUserId("")).isEmpty();
        assertThat(service.findByUserId("  ")).isEmpty();
        verify(mapper, never()).selectList(any());
    }

    private static UserKnowledgeStateDO state(String topic, Double masteryScore) {
        UserKnowledgeStateDO state = new UserKnowledgeStateDO();
        state.setTopic(topic);
        state.setMasteryScore(masteryScore);
        return state;
    }
}
