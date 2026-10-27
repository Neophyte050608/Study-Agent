package io.github.imzmq.interview.modelrouting.infrastructure.planning;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingDynamicPlanningModelTest {

    @Test
    void routesPlanningThroughTheGeneralModelBoundary() {
        RoutingChatService routing = mock(RoutingChatService.class);
        when(routing.call("system", "user", ModelRouteType.GENERAL, "受控动态计划影子"))
                .thenReturn("plan-json");
        ModelRoutingDynamicPlanningModel model = new ModelRoutingDynamicPlanningModel(routing);

        assertThat(model.generatePlan("system", "user")).isEqualTo("plan-json");
        verify(routing).call("system", "user", ModelRouteType.GENERAL, "受控动态计划影子");
    }
}
