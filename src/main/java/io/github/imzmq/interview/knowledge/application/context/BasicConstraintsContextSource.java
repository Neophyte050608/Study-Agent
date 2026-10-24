package io.github.imzmq.interview.knowledge.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSource;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BasicConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "basic-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        return List.of(
                new ContextItem("优先基于给定知识上下文和证据回答；证据不足时明确说明不确定。", 1.0, id(), Map.of()),
                new ContextItem("不要编造不存在的引用、图片编号或文档来源。", 1.0, id(), Map.of())
        );
    }
}
