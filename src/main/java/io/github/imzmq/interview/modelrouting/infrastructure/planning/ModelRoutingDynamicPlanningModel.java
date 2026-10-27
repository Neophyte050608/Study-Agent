package io.github.imzmq.interview.modelrouting.infrastructure.planning;

import io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ModelRoutingDynamicPlanningModel implements DynamicPlanningModelOperations {
    private static final String STAGE = "受控动态计划影子";

    private final RoutingChatService routing;

    public ModelRoutingDynamicPlanningModel(RoutingChatService routing) {
        this.routing = Objects.requireNonNull(routing);
    }

    @Override
    public String generatePlan(String systemPrompt, String userPrompt) {
        return routing.call(systemPrompt, userPrompt, ModelRouteType.GENERAL, STAGE);
    }
}
