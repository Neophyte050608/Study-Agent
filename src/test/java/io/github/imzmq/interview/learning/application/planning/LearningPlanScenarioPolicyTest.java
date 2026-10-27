package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LearningPlanScenarioPolicyTest {

    private final LearningPlanScenarioPolicy policy = new LearningPlanScenarioPolicy();

    @Test
    void exposesTheLearningPreviewScenarioAndOnlyItsThreeCapabilities() {
        assertThat(policy.scenarioId()).isEqualTo("learning-plan-preview");
        assertThat(policy.allowedCapabilities()).containsExactlyInAnyOrder(
                "learning.profile.lookup",
                "knowledge.evidence.retrieve",
                "learning.plan.compose");
    }

    @Test
    void acceptsOnlyTheThreeApprovedShapes() {
        ValidatedDynamicTaskPlan profileEvidenceCompose = plan(
                profile("profile"),
                evidence("evidence"),
                compose("compose", "profile", "evidence"));
        ValidatedDynamicTaskPlan profileCompose = plan(
                profile("profile"),
                compose("compose", "profile"));
        ValidatedDynamicTaskPlan evidenceCompose = plan(
                evidence("evidence"),
                compose("compose", "evidence"));

        assertThat(policy.validate(profileEvidenceCompose)).isEmpty();
        assertThat(policy.validate(profileCompose)).isEmpty();
        assertThat(policy.validate(evidenceCompose)).isEmpty();
    }

    @Test
    void rejectsComposeOnlyDuplicateSourcesAndNonTerminalCompose() {
        ValidatedDynamicTaskPlan composeOnly = plan(compose("compose"));
        ValidatedDynamicTaskPlan duplicateProfiles = plan(
                profile("profile-one"),
                profile("profile-two"),
                compose("compose", "profile-one", "profile-two"));
        ValidatedDynamicTaskPlan nonTerminalCompose = plan(
                profile("profile"),
                compose("compose", "profile"),
                evidenceDependingOn("evidence", "compose"));

        assertViolation(composeOnly);
        assertViolation(duplicateProfiles);
        assertViolation(nonTerminalCompose);
    }

    @Test
    void rejectsMissingOrDuplicateComposeAndUnexpectedPlanSizes() {
        assertViolation(plan(profile("profile"), evidence("evidence")));
        assertViolation(plan(
                profile("profile"),
                compose("compose-one", "profile"),
                compose("compose-two", "profile")));
        assertViolation(plan(
                profile("profile"),
                evidence("evidence"),
                source("extra", "knowledge.evidence.retrieve"),
                compose("compose", "profile", "evidence", "extra")));
    }

    @Test
    void rejectsDependenciesBetweenSourcesAndComposeDependenciesThatDoNotMatchSources() {
        ValidatedDynamicTaskPlan sourcesDependOnEachOther = plan(
                profileDependingOn("profile", "evidence"),
                evidence("evidence"),
                compose("compose", "profile", "evidence"));
        ValidatedDynamicTaskPlan composeMissesEvidence = plan(
                profile("profile"),
                evidence("evidence"),
                compose("compose", "profile"));
        ValidatedDynamicTaskPlan composeUsesUnknownNode = plan(
                profile("profile"),
                compose("compose", "missing"));

        assertViolation(sourcesDependOnEachOther);
        assertViolation(composeMissesEvidence);
        assertViolation(composeUsesUnknownNode);
    }

    private void assertViolation(ValidatedDynamicTaskPlan plan) {
        assertThat(policy.validate(plan))
                .extracting(DynamicPlanIssue::code)
                .contains("SCENARIO_POLICY_VIOLATION");
    }

    private static ValidatedDynamicTaskPlan plan(ValidatedDynamicTaskNode... nodes) {
        return new ValidatedDynamicTaskPlan(
                "1", "learning-plan-preview", List.of(nodes), nodes.length);
    }

    private static ValidatedDynamicTaskNode profile(String id) {
        return source(id, "learning.profile.lookup");
    }

    private static ValidatedDynamicTaskNode profileDependingOn(String id, String dependency) {
        return new ValidatedDynamicTaskNode(
                id, "learning.profile.lookup", 1, List.of(dependency), Map.of());
    }

    private static ValidatedDynamicTaskNode evidence(String id) {
        return source(id, "knowledge.evidence.retrieve");
    }

    private static ValidatedDynamicTaskNode evidenceDependingOn(String id, String dependency) {
        return new ValidatedDynamicTaskNode(
                id, "knowledge.evidence.retrieve", 1, List.of(dependency), Map.of());
    }

    private static ValidatedDynamicTaskNode compose(String id, String... dependencies) {
        return new ValidatedDynamicTaskNode(
                id, "learning.plan.compose", 1, List.of(dependencies), Map.of());
    }

    private static ValidatedDynamicTaskNode source(String id, String capability) {
        return new ValidatedDynamicTaskNode(id, capability, 1, List.of(), Map.of());
    }
}
