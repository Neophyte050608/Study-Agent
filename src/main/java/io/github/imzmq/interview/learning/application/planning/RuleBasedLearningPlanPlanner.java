package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RuleBasedLearningPlanPlanner {
    private static final Set<String> PROFILE_KEYWORDS = Set.of(
            "薄弱", "历史", "表现", "个性化", "画像", "掌握",
            "history", "profile", "personalized", "weakness");
    private static final Set<String> EVIDENCE_KEYWORDS = Set.of(
            "资料", "知识库", "证据", "参考", "题目", "文档",
            "evidence", "reference", "knowledge", "document");

    public CandidateDynamicTaskPlan plan(String topic, String request) {
        String text = ((topic == null ? "" : topic) + " " + (request == null ? "" : request))
                .toLowerCase(Locale.ROOT);
        boolean includeProfile = containsAny(text, PROFILE_KEYWORDS);
        boolean includeEvidence = containsAny(text, EVIDENCE_KEYWORDS);
        if (!includeProfile && !includeEvidence) {
            includeProfile = true;
            includeEvidence = true;
        }

        List<CandidateDynamicTaskNode> nodes = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        if (includeProfile) {
            nodes.add(node("profile", "learning.profile.lookup", List.of(), Map.of()));
            dependencies.add("profile");
        }
        if (includeEvidence) {
            nodes.add(node("evidence", "knowledge.evidence.retrieve", List.of(), Map.of("limit", 4)));
            dependencies.add("evidence");
        }
        nodes.add(node("compose", "learning.plan.compose", dependencies, Map.of()));
        return new CandidateDynamicTaskPlan("1", nodes);
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private static CandidateDynamicTaskNode node(
            String id,
            String capability,
            List<String> dependencies,
            Map<String, Object> arguments) {
        return new CandidateDynamicTaskNode(id, capability, dependencies, arguments);
    }
}
