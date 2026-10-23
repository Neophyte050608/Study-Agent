package io.github.imzmq.interview.routing.application.understanding;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentLlmResolverTest {

    @Test
    void callsModelExactlyOnceWithCompleteStructuredPrompt() {
        RoutingChatService chatService = mock(RoutingChatService.class);
        IntentRecordMapper mapper = mock(IntentRecordMapper.class);
        IntentRecord expected = IntentRecord.fallback("s", "q", "mapped");
        when(chatService.call(anyString(), anyString(), eq(ModelRouteType.GENERAL), eq("意图统一理解")))
                .thenReturn("{json}");
        when(mapper.map("s", "q", "{json}")).thenReturn(expected);
        IntentLlmResolver resolver = new IntentLlmResolver(chatService, mapper);

        assertThat(resolver.resolve("s", "u", "q", "previous Redis discussion")).isSameAs(expected);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(1)).call(system.capture(), user.capture(), eq(ModelRouteType.GENERAL), eq("意图统一理解"));
        assertThat(system.getValue()).contains("NEW_TOPIC", "FOLLOW_UP", "REFINE", "CORRECT", "RETURN", "CONFIRM", "CHITCHAT")
                .contains("只返回 JSON", "最多一个任务");
        assertThat(user.getValue()).contains("previous Redis discussion", "q");
        verify(mapper).map("s", "q", "{json}");
    }
}
