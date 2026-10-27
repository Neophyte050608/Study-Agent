package io.github.imzmq.interview.architecture;

import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSource;
import io.github.imzmq.interview.agent.application.contract.extension.TaskHandler;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.imzmq.interview.architecture.fixture.api.ControllerDependingOnMapper;
import io.github.imzmq.interview.architecture.fixture.application.ApplicationServiceDependingOnMapper;
import io.github.imzmq.interview.architecture.fixture.infrastructure.persistence.SamplePersistenceDO;
import io.github.imzmq.interview.architecture.fixture.infrastructure.persistence.SamplePersistenceMapper;
import io.github.imzmq.interview.architecturefixturesource.application.CrossModulePersistenceConsumer;
import io.github.imzmq.interview.architecturefixturesource.application.ForeignContractConsumer;
import io.github.imzmq.interview.architecturefixturesource.application.ForeignImplementationConsumer;
import io.github.imzmq.interview.architecturefixturesource.application.LocalApplicationService;
import io.github.imzmq.interview.architecturefixturesource.application.SameModuleInternalConsumer;
import io.github.imzmq.interview.architecturefixturesource.application.contract.SourcePublicOperation;
import io.github.imzmq.interview.architecturefixturetarget.application.InternalApplicationService;
import io.github.imzmq.interview.architecturefixturetarget.application.ReverseContractConsumer;
import io.github.imzmq.interview.architecturefixturetarget.application.contract.PublicOperation;
import io.github.imzmq.interview.architecturefixturetarget.infrastructure.persistence.ForeignPersistenceDO;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@AnalyzeClasses(
        packages = "io.github.imzmq.interview",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class ArchitectureRulesTest {

    @Test
    void learning_api_dynamic_planning_rule_catches_planner_dependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                io.github.imzmq.interview.learning.api.dynamicfixture.ControllerDependingOnPlanner.class);

        assertThat(learning_api_should_not_access_dynamic_planning_internals
                .evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void dynamic_planning_core_rule_catches_spring_ai_dependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                io.github.imzmq.interview.agent.application.execution.dynamic.fixture.CoreDependingOnSpringAi.class);

        assertThat(dynamic_planning_core_should_be_sdk_neutral
                .evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void learning_plan_capability_rule_catches_persistence_dependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                io.github.imzmq.interview.learning.application.planning.fixture.LearningPlanPersistenceCapability.class,
                SamplePersistenceMapper.class,
                SamplePersistenceDO.class);

        assertThat(learning_plan_capabilities_should_not_access_execution_or_persistence_internals
                .evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void dynamic_planning_contract_rule_catches_jackson_dependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                io.github.imzmq.interview.agent.application.contract.execution.dynamic.fixture.ContractExposingJsonNode.class);

        assertThat(dynamic_planning_public_contracts_should_be_technology_neutral
                .evaluate(fixture).hasViolation()).isTrue();
    }

    @ArchTest
    static final ArchRule learning_api_should_not_access_dynamic_planning_internals =
            noClasses().that().resideInAPackage("..learning.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..agent.application.execution.dynamic..",
                            "..agent.infrastructure.planning..",
                            "..learning.application.planning..")
                    .because("Learning HTTP may use application contracts but not dynamic-planning implementations");

    @ArchTest
    static final ArchRule dynamic_planning_core_should_be_sdk_neutral =
            noClasses().that().resideInAnyPackage(
                            "..agent.application.contract.execution.dynamic..",
                            "..agent.application.execution.dynamic..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.ai..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "io.milvus..",
                            "org.neo4j..",
                            "dev.langchain4j..",
                            "io.github.imzmq.interview.learning..",
                            "io.github.imzmq.interview.knowledge..")
                    .because("generic dynamic-plan contracts, validation, and compilation must remain SDK and product neutral");

    @ArchTest
    static final ArchRule agent_planning_infrastructure_should_not_access_model_sdks_directly =
            noClasses().that().resideInAPackage("..agent.infrastructure.planning..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
                    .because("Agent planning infrastructure must enter models through ModelRouting's public contract");

    @ArchTest
    static final ArchRule agent_planning_infrastructure_should_only_use_modelrouting_planning_contract =
            noClasses().that().resideInAPackage("..agent.infrastructure.planning..")
                    .should().dependOnClassesThat(new DescribedPredicate<>("ModelRouting types outside DynamicPlanningModelOperations") {
                        @Override
                        public boolean test(JavaClass item) {
                            return item.getPackageName().startsWith("io.github.imzmq.interview.modelrouting.")
                                    && !item.getName().equals(
                                            "io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations");
                        }
                    })
                    .because("the provider-owned planning contract preserves the one-way Agent to ModelRouting dependency");

    @ArchTest
    static final ArchRule learning_plan_capabilities_should_not_access_execution_or_persistence_internals =
            noClasses().that().resideInAPackage("..learning.application.planning..")
                    .and().haveSimpleNameStartingWith("LearningPlan")
                    .and().haveSimpleNameEndingWith("Capability")
                    .should().dependOnClassesThat(new DescribedPredicate<>("persistence, tool, MCP, or Agent runtime types") {
                        @Override
                        public boolean test(JavaClass item) {
                            String packageName = item.getPackageName();
                            return areMyBatisMappers().test(item)
                                    || packageName.contains(".infrastructure.persistence")
                                    || packageName.matches(".*\\.(tool|mcp)($|\\..*)")
                                    || packageName.startsWith("io.github.imzmq.interview.agent.runtime");
                        }
                    })
                    .because("Learning dynamic capabilities are bounded business operations, not infrastructure executors");

    @ArchTest
    static final ArchRule dynamic_planning_public_contracts_should_be_technology_neutral =
            noClasses().that().resideInAPackage("..application.contract.execution.dynamic..")
                    .or().haveSimpleName("DynamicNodeCapability")
                    .or().haveSimpleName("DynamicPlanningModelOperations")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fasterxml.jackson..",
                            "org.springframework.ai..",
                            "org.springframework.web..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "io.milvus..",
                            "org.neo4j..",
                            "dev.langchain4j..",
                            "io.github.imzmq.interview..infrastructure..")
                    .because("public planning contracts must expose only stable technology-neutral types");

    @ArchTest
    static final ArchRule benchmark_application_should_not_depend_on_infrastructure_sdks =
            noClasses().that().resideInAnyPackage(
                            "..knowledge.application.evaluation.goldset..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.milvus..",
                            "org.springframework.ai.embedding..",
                            "..knowledge.infrastructure.evaluation..")
                    .because("benchmark application code must remain SDK-neutral and depend on ports only");

    @Test
    void learning_api_rules_catch_agent_access_and_json_decoding() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                io.github.imzmq.interview.architecture.fixture.learning.api.ControllerDependingOnExecution.class);
        assertThat(learning_api_should_not_access_agent_runtime_or_decode_execution_json.evaluate(fixture).hasViolation()).isTrue();
        assertThat(learning_api_should_not_decode_persisted_json.evaluate(fixture).hasViolation()).isTrue();
    }

    @ArchTest
    static final ArchRule learning_api_should_not_access_agent_runtime_or_decode_execution_json =
            noClasses().that().resideInAPackage("..learning.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.agent..", "..infrastructure..",
                            "io.github.imzmq.interview.learning.application.planning..")
                    .because("Learning HTTP only consumes Learning-owned application contracts");

    @ArchTest
    static final ArchRule learning_api_should_not_decode_persisted_json =
            noClasses().that().resideInAPackage("..learning.api..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.fasterxml.jackson.databind.ObjectMapper")
                    .because("persisted output projection belongs to Learning application, not HTTP");

    @ArchTest
    static final ArchRule learning_preview_should_not_have_business_write_or_tool_dependencies =
            noClasses().that().resideInAPackage("..learning.application.planning..")
                    .or().haveSimpleName("LearningPlanPreviewTaskHandler")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..tool..", "..mcp..", "..skill..", "..infrastructure..",
                            "java.nio.file..", "java.io..")
                    .because("the fixed learning-plan preview only reads materials and generates content");

    @ArchTest
    static final ArchRule learning_preview_should_not_reuse_note_generation =
            noClasses().that().resideInAPackage("..learning.application.planning..")
                    .or().haveSimpleName("LearningPlanPreviewTaskHandler")
                    .should().dependOnClassesThat(new DescribedPredicate<>("legacy note or learning-plan generation") {
                        @Override
                        public boolean test(JavaClass item) {
                            return Set.of("NoteMakingAgent", "LearningPlanTaskHandler", "LearningPlanGenerator")
                                    .contains(item.getSimpleName());
                        }
                    });

    @ArchTest
    static final ArchRule preview_evidence_adapter_should_only_search_lexical_index =
            classes().that().haveSimpleName("LexicalLearningPlanEvidenceReader")
                    .should(new ArchCondition<>("only call search on LexicalIndexService") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            item.getMethodCallsFromSelf().stream()
                                    .filter(call -> call.getTargetOwner().getSimpleName().equals("LexicalIndexService"))
                                    .filter(call -> !call.getTarget().getName().equals("search"))
                                    .forEach(call -> events.add(SimpleConditionEvent.violated(item, call.getDescription())));
                        }
                    });

    @ArchTest
    static final ArchRule learning_preview_should_only_read_profile_operations =
            classes().that().resideInAPackage("..learning.application.planning..")
                    .should(new ArchCondition<>("only call snapshot on LearningProfileOperations") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            item.getMethodCallsFromSelf().stream()
                                    .filter(call -> call.getTargetOwner().getSimpleName().equals("LearningProfileOperations"))
                                    .filter(call -> !call.getTarget().getName().equals("snapshot"))
                                    .forEach(call -> events.add(SimpleConditionEvent.violated(item, call.getDescription())));
                        }
                    });

    @ArchTest
    static final ArchRule preview_adapters_should_not_execute_skills_tools_or_full_rag =
            noClasses().that().resideInAPackage("..knowledge.application.planning..")
                    .should().dependOnClassesThat(new DescribedPredicate<>("tool, write, or full RAG implementation") {
                        @Override
                        public boolean test(JavaClass item) {
                            return item.getPackageName().matches(".*\\.(tool|mcp|skill)($|\\..*)")
                                    || item.getPackageName().contains(".infrastructure.")
                                    || Set.of("RAGService", "KnowledgePacketBuilder", "NoteMakingAgent")
                                            .contains(item.getSimpleName());
                        }
                    });

    @ArchTest
    static final ArchRule preview_generator_should_not_repeat_retrieval =
            noClasses().that().haveSimpleName("ModelLearningPlanPreviewGenerator")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..knowledge.application.indexing..", "..knowledge.application.retrieval..",
                            "..knowledge.application.evidence..", "..search..", "..media..");

    @ArchTest
    static final ArchRule fixed_dag_scheduler_should_not_own_business_nodes =
            noClasses().that().resideInAnyPackage("..agent.application.execution..", "..agent.domain.execution..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..learning..", "..knowledge..", "..practice..", "..chat..", "..interview.application..",
                            "..tool..", "..mcp..", "..skill..");

    @ArchTest
    static final ArchRule rag_facade_should_not_own_retrieval_adapters =
            noClasses().that().haveFullyQualifiedName("io.github.imzmq.interview.knowledge.application.RAGService")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.ai.vectorstore..", "..knowledge.infrastructure..", "..knowledge.application.indexing..");

    @ArchTest
    static final ArchRule packet_builder_should_not_call_back_into_rag_delegate =
            noClasses().that().haveFullyQualifiedName("io.github.imzmq.interview.knowledge.application.KnowledgePacketBuilder")
                    .should().dependOnClassesThat().haveSimpleName("RetrievalDelegate");

    @ArchTest
    static final ArchRule evidence_fusion_should_not_access_retrieval_adapters =
            noClasses().that().haveSimpleName("EvidenceFusionService")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..knowledge.infrastructure..", "org.springframework.ai.vectorstore..", "..media..", "..search..");

    @ArchTest
    static final ArchRule grounding_should_not_recall_or_search_externally =
            noClasses().that().haveSimpleName("GroundingContextAssembler")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.ai.vectorstore..", "..knowledge.infrastructure.graph..", "..search..");

    @ArchTest
    static final ArchRule controller_should_not_access_mapper_directly =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat(areMyBatisMappers());

    @Test
    void controller_mapper_rule_catches_mapper_interfaces_in_infrastructure_persistence() {
        EvaluationResult result = controller_should_not_access_mapper_directly.evaluate(importRuleFixtureClasses());

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> {
                    assertThat(detail).contains(ControllerDependingOnMapper.class.getSimpleName());
                    assertThat(detail).contains(SamplePersistenceMapper.class.getSimpleName());
                });
    }

    @Test
    void controller_mapper_rule_allows_application_services_to_depend_on_mappers() {
        ArchRule applicationServiceRule =
                noClasses().that().resideInAnyPackage("..application..")
                        .should().dependOnClassesThat(areMyBatisMappers());

        EvaluationResult result = applicationServiceRule.evaluate(importRuleFixtureClasses());

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> {
                    assertThat(detail).contains(ApplicationServiceDependingOnMapper.class.getSimpleName());
                    assertThat(detail).contains(SamplePersistenceMapper.class.getSimpleName());
                });
        EvaluationResult controllerRuleResult = controller_should_not_access_mapper_directly.evaluate(importRuleFixtureClasses());
        assertThat(controllerRuleResult.getFailureReport().getDetails())
                .noneSatisfy(detail ->
                        assertThat(detail).contains(ApplicationServiceDependingOnMapper.class.getSimpleName()));
    }

    @ArchTest
    static final ArchRule modules_should_not_access_other_modules_persistence =
            ModuleBoundaryRules.noCrossModulePersistenceAccess(
                    LegacyCrossModulePersistenceDependencies.keys()
            );

    @ArchTest
    static final ArchRule modules_should_only_access_other_modules_through_application_contracts =
            ModuleBoundaryRules.onlyAccessOtherModulesThroughApplicationContracts();

    @ArchTest
    static final ArchRule top_level_modules_should_be_acyclic =
            ModuleBoundaryRules.topLevelModulesShouldBeAcyclic();

    @ArchTest
    static final ArchRule business_modules_should_not_depend_on_agent_a2a_internals =
            noClasses().that().resideOutsideOfPackage("io.github.imzmq.interview.agent..")
                    .and().resideOutsideOfPackage("io.github.imzmq.interview.config..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.agent.a2a..");

    @ArchTest
    static final ArchRule business_modules_should_not_depend_on_agent_runtime_implementations =
            noClasses().that().resideOutsideOfPackage("io.github.imzmq.interview.agent..")
                    .and().resideOutsideOfPackage("io.github.imzmq.interview.config..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.agent.runtime..");

    @ArchTest
    static final ArchRule business_modules_should_not_depend_on_interview_domain =
            noClasses().that().resideOutsideOfPackage("io.github.imzmq.interview.interview..")
                    .and().resideOutsideOfPackage("io.github.imzmq.interview.config..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.domain..");

    @ArchTest
    static final ArchRule public_application_contracts_should_not_expose_private_or_technical_types =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview..application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview..domain..",
                            "io.github.imzmq.interview..infrastructure..",
                            "org.springframework.web..",
                            "org.springframework.ai..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule common_should_not_depend_on_product_or_platform_modules =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.agent..",
                            "io.github.imzmq.interview.chat..",
                            "io.github.imzmq.interview.feedback..",
                            "io.github.imzmq.interview.identity..",
                            "io.github.imzmq.interview.im..",
                            "io.github.imzmq.interview.ingestion..",
                            "io.github.imzmq.interview.interview..",
                            "io.github.imzmq.interview.knowledge..",
                            "io.github.imzmq.interview.learning..",
                            "io.github.imzmq.interview.mcp..",
                            "io.github.imzmq.interview.media..",
                            "io.github.imzmq.interview.menu..",
                            "io.github.imzmq.interview.modelrouting..",
                            "io.github.imzmq.interview.observability..",
                            "io.github.imzmq.interview.practice..",
                            "io.github.imzmq.interview.routing..",
                            "io.github.imzmq.interview.search..",
                            "io.github.imzmq.interview.security..",
                            "io.github.imzmq.interview.skill..",
                            "io.github.imzmq.interview.tool..");

    @ArchTest
    static final ArchRule observability_should_not_depend_on_knowledge =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.observability..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge..");

    @ArchTest
    static final ArchRule domain_should_not_depend_on_api_infrastructure_or_technical_frameworks =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview..api..",
                            "io.github.imzmq.interview..infrastructure..",
                            "org.springframework.web..",
                            "org.springframework.ai..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule agent_should_not_depend_on_controller =
            noClasses().that().resideInAnyPackage("..agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..api..");

    @ArchTest
    static final ArchRule controllers_should_not_depend_on_external_sdks_or_runtime_implementations =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.agent.runtime..",
                            "io.github.imzmq.interview..application.agent..",
                            "io.github.imzmq.interview.tool.runtime..",
                            "org.springframework.ai..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule entity_should_not_depend_on_service_or_controller =
            noClasses().that().resideInAnyPackage("..entity..")
                    .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..api..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule retired_packages_should_not_exist =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.entity..",
                            "io.github.imzmq.interview.mapper..",
                            "io.github.imzmq.interview.dto..",
                            "io.github.imzmq.interview.stream..",
                            "io.github.imzmq.interview.intent..",
                            "io.github.imzmq.interview.turn..",
                            "io.github.imzmq.interview.graph..",
                            "io.github.imzmq.interview.rag..",
                            "io.github.imzmq.interview.knowledge.application.chatstream..",
                            "io.github.imzmq.interview.knowledge.application.coding..",
                            "io.github.imzmq.interview.knowledge.application.observability.."
                    )
                    .should(exist())
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule generic_chat_controller_should_belong_to_chat_api =
            classes().that().haveSimpleName("ChatController")
                    .should().resideInAPackage("io.github.imzmq.interview.chat.api");

    @ArchTest
    static final ArchRule generic_chat_application_services_should_belong_to_chat =
            classes().that().haveSimpleName("WebChatService")
                    .or().haveSimpleName("ChatStreamingService")
                    .or().haveSimpleName("ChatStreamingSupport")
                    .or().haveSimpleName("ChunkedTextStreamer")
                    .or().haveSimpleName("ChatIntentHeuristics")
                    .should().resideInAPackage("io.github.imzmq.interview.chat.application");

    @ArchTest
    static final ArchRule chat_scenario_extension_types_should_belong_to_chat_contract =
            classes().that().haveSimpleName("ChatScenarioHandler")
                    .or().haveSimpleName("StreamingChatContext")
                    .should().resideInAPackage("io.github.imzmq.interview.chat.application.contract.extension");

    @ArchTest
    static final ArchRule business_chat_handlers_should_only_use_chat_public_contracts =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application.chat..",
                            "io.github.imzmq.interview.knowledge.application.chat..",
                            "io.github.imzmq.interview.practice.application.chat..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.imzmq.interview.chat.application");

    @ArchTest
    static final ArchRule chat_should_not_depend_on_concrete_scenario_handlers =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application.chat..",
                            "io.github.imzmq.interview.knowledge.application.chat..",
                            "io.github.imzmq.interview.practice.application.chat..");

    @ArchTest
    static final ArchRule chat_should_not_depend_on_interview_implementation =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application..",
                            "io.github.imzmq.interview.interview.domain..",
                            "io.github.imzmq.interview.interview.infrastructure..");

    @ArchTest
    static final ArchRule interview_should_not_depend_on_chat_infrastructure =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.interview..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.chat.infrastructure..");

    @ArchTest
    static final ArchRule interview_specific_implementations_should_belong_to_interview =
            classes().that().haveSimpleName("InterviewOrchestratorAgent")
                    .or().haveSimpleName("EvaluationAgent")
                    .or().haveSimpleName("KnowledgeLayerAgent")
                    .or().haveSimpleName("RollingSummaryAgent")
                    .or().haveSimpleName("InterviewStartTaskHandler")
                    .or().haveSimpleName("InterviewAnswerTaskHandler")
                    .or().haveSimpleName("InterviewReportTaskHandler")
                    .or().haveSimpleName("InterviewContextAttributes")
                    .or().haveSimpleName("InterviewProfileContextSource")
                    .or().haveSimpleName("InterviewStrategyContextSource")
                    .or().haveSimpleName("InterviewKnowledgeContextSource")
                    .or().haveSimpleName("InterviewAnswerEvaluationService")
                    .or().haveSimpleName("ResumeLoader")
                    .or().haveSimpleName("InterviewReportGeneratorSkill")
                    .should().resideInAnyPackage("io.github.imzmq.interview.interview..");

    @ArchTest
    static final ArchRule generic_modules_should_not_depend_on_concrete_interview_implementations =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.agent..",
                            "io.github.imzmq.interview.knowledge..",
                            "io.github.imzmq.interview.rag..",
                            "io.github.imzmq.interview.skill..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application.agent..",
                            "io.github.imzmq.interview.interview.application.context..",
                            "io.github.imzmq.interview.interview.application.evaluation..",
                            "io.github.imzmq.interview.interview.application.skill..",
                            "io.github.imzmq.interview.interview.infrastructure.resume..");

    @ArchTest
    static final ArchRule interview_contracts_should_not_expose_technical_or_persistence_types =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.interview.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.infrastructure..",
                            "org.springframework.web..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule agent_extension_contracts_should_belong_to_public_extension_package =
            classes().that().haveSimpleName("TaskHandler")
                    .or().haveSimpleName("AgentContextSource")
                    .or().haveSimpleName("AgentContextSchemaProvider")
                    .should().resideInAPackage(
                            "io.github.imzmq.interview.agent.application.contract.extension");

    @ArchTest
    static final ArchRule generic_agent_should_not_depend_on_business_agent_or_context_implementations =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.agent..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.application.agent..",
                            "io.github.imzmq.interview.knowledge.application.context..",
                            "io.github.imzmq.interview.learning.application.agent..",
                            "io.github.imzmq.interview.learning.application.context..",
                            "io.github.imzmq.interview.interview.application.agent..",
                            "io.github.imzmq.interview.interview.application.context..",
                            "io.github.imzmq.interview.practice.application.agent..",
                            "io.github.imzmq.interview.practice.application.context..");

    @ArchTest
    static final ArchRule agent_public_contract_should_not_expose_technical_or_persistence_types =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.agent.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..infrastructure..",
                            "org.springframework.web..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule knowledge_agent_implementations_should_belong_to_knowledge =
            classes().that().haveSimpleName("KnowledgeQaAgent")
                    .or().haveSimpleName("KnowledgeQaTaskHandler")
                    .or().haveSimpleName("KnowledgeQaContextAttributes")
                    .or().haveSimpleName("DialogSignalContextSource")
                    .or().haveSimpleName("KnowledgeContextSource")
                    .should().resideInAnyPackage("io.github.imzmq.interview.knowledge..");

    @ArchTest
    static final ArchRule learning_agent_implementations_should_belong_to_learning =
            classes().that().haveSimpleName("NoteMakingAgent")
                    .or().haveSimpleName("LearningPlanTaskHandler")
                    .or().haveSimpleName("ProfileEventUpsertTaskHandler")
                    .or().haveSimpleName("ProfileSnapshotQueryTaskHandler")
                    .or().haveSimpleName("ProfileTrainingPlanQueryTaskHandler")
                    .or().haveSimpleName("LearningProfileContextSource")
                    .should().resideInAnyPackage("io.github.imzmq.interview.learning..");

    @ArchTest
    static final ArchRule coding_practice_implementations_should_belong_to_practice =
            classes().that().haveSimpleName("CodingPracticeController")
                    .or().haveSimpleName("CodingPracticeService")
                    .or().haveSimpleName("CodingPracticeAgent")
                    .or().haveSimpleName("CodingPracticeTaskHandler")
                    .or().haveSimpleName("ActiveCodingChatScenarioHandler")
                    .or().haveSimpleName("CodingPracticeCardChatScenarioHandler")
                    .or().haveSimpleName("CodingPracticeTaskResultPresenter")
                    .or().haveSimpleName("CodingPracticeContextAttributes")
                    .or().haveSimpleName("CodingConstraintsContextSource")
                    .or().haveSimpleName("CodingProfileContextSource")
                    .or().haveSimpleName("CodingTaskPlanContextSource")
                    .or().haveSimpleName("CodingInterviewCoachSkill")
                    .should().resideInAnyPackage("io.github.imzmq.interview.practice..");

    @ArchTest
    static final ArchRule observability_persistence_should_belong_to_observability =
            classes().that().haveSimpleName("RagTraceDO")
                    .or().haveSimpleName("RagTraceMapper")
                    .or().haveSimpleName("RagTraceNodeDO")
                    .or().haveSimpleName("RagTraceNodeMapper")
                    .or().haveSimpleName("RagMetricsSnapshotDO")
                    .or().haveSimpleName("RagMetricsSnapshotMapper")
                    .should().resideInAPackage(
                            "io.github.imzmq.interview.observability.infrastructure.persistence");

    @ArchTest
    static final ArchRule feedback_persistence_should_belong_to_feedback =
            classes().that().haveSimpleName("RagFeedbackDO")
                    .or().haveSimpleName("RagFeedbackMapper")
                    .should().resideInAPackage(
                            "io.github.imzmq.interview.feedback.infrastructure.persistence");

    @ArchTest
    static final ArchRule observability_and_feedback_should_not_depend_on_knowledge_persistence =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.observability..",
                            "io.github.imzmq.interview.feedback..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.infrastructure.persistence..");

    @ArchTest
    static final ArchRule media_should_not_depend_on_knowledge_persistence =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.media..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.infrastructure.persistence..");

    @ArchTest
    static final ArchRule external_modules_should_not_depend_on_knowledge_graph_infrastructure =
            noClasses().that().resideOutsideOfPackage("io.github.imzmq.interview.knowledge..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.infrastructure.graph..");

    @ArchTest
    static final ArchRule knowledge_contract_should_not_expose_framework_or_persistence_types =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.infrastructure..",
                            "org.springframework..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..");

    @ArchTest
    static final ArchRule search_should_not_depend_on_learning_persistence =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.search..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.learning.infrastructure.persistence..");

    @ArchTest
    static final ArchRule learning_contract_should_not_expose_framework_or_persistence_types =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.learning.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.learning.infrastructure..",
                            "org.springframework..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..");

    @ArchTest
    static final ArchRule feedback_contract_should_not_expose_framework_or_persistence_types =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.feedback.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.feedback.infrastructure..",
                            "org.springframework..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..");

    @ArchTest
    static final ArchRule generic_modules_should_not_depend_on_concrete_practice_implementations =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.agent..",
                            "io.github.imzmq.interview.chat..",
                            "io.github.imzmq.interview.interview..",
                            "io.github.imzmq.interview.knowledge..",
                            "io.github.imzmq.interview.skill..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.github.imzmq.interview.practice..");

    @ArchTest
    static final ArchRule chat_memory_domain_should_be_framework_and_adapter_free =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat.domain.memory..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.chat.api..",
                            "io.github.imzmq.interview.chat.infrastructure..",
                            "org.springframework..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "io.milvus..");

    @ArchTest
    static final ArchRule chat_api_should_not_access_persistence_or_milvus =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.chat.infrastructure.persistence..",
                            "io.github.imzmq.interview.chat.infrastructure.milvus..",
                            "io.milvus..");

    @ArchTest
    static final ArchRule chat_contracts_should_not_expose_technical_or_persistence_types =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..infrastructure.persistence..",
                            "io.github.imzmq.interview.chat.infrastructure.milvus..",
                            "org.springframework.web..",
                            "org.springframework.ai..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "io.milvus..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule knowledge_should_not_access_chat_memory_persistence =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.knowledge..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.chat.infrastructure.persistence.memory..");

    @ArchTest
    static final ArchRule only_chat_milvus_adapter_should_import_milvus_sdk =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat..")
                    .and().resideOutsideOfPackage("io.github.imzmq.interview.chat.infrastructure.milvus..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.milvus..");

    @ArchTest
    static final ArchRule legacy_memory_text_writers_should_not_exist =
            noClasses().that().haveSimpleName("ChatMemoryExtractorAgent")
                    .or().haveSimpleName("AutoDreamService")
                    .should(exist())
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule modelrouting_should_not_depend_on_chat =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.modelrouting..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.github.imzmq.interview.chat..");

    @ArchTest
    static final ArchRule structured_output_contract_types_should_belong_to_modelrouting_contract =
            classes().that().haveSimpleName("StructuredOutputParser")
                    .or().haveSimpleName("StructuredOutputResult")
                    .or().haveSimpleName("StructuredOutputSchema")
                    .or().haveSimpleName("StructuredOutputRetry")
                    .should().resideInAPackage(
                            "io.github.imzmq.interview.modelrouting.application.contract.structuredoutput");

    @ArchTest
    static final ArchRule default_structured_output_parser_should_belong_to_modelrouting_implementation =
            classes().that().haveSimpleName("DefaultStructuredOutputParser")
                    .should().resideInAPackage(
                            "io.github.imzmq.interview.modelrouting.application.structuredoutput");

    @ArchTest
    static final ArchRule external_modules_should_only_use_structured_output_contract =
            noClasses().that().resideOutsideOfPackage("io.github.imzmq.interview.modelrouting..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.github.imzmq.interview.modelrouting.application.structuredoutput..");

    @ArchTest
    static final ArchRule legacy_chat_structured_output_types_should_not_exist =
            noClasses().that().haveSimpleName("LlmJsonParser")
                    .or().haveSimpleName("JsonResult")
                    .or().haveSimpleName("SchemaSpec")
                    .or().haveSimpleName("RetryCall")
                    .should(exist())
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule structured_output_contract_should_not_expose_framework_or_persistence_types =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.modelrouting.application.contract.structuredoutput..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.modelrouting.infrastructure..",
                            "org.springframework..",
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "dev.langchain4j..",
                            "com.volcengine..");

    @ArchTest
    static final ArchRule intent_record_should_stay_in_routing_public_result_contract =
            classes().that().haveSimpleName("IntentRecord")
                    .should().resideInAPackage("io.github.imzmq.interview.routing.application.contract.result");

    @ArchTest
    static final ArchRule understanding_service_should_stay_in_routing_public_contract =
            classes().that().haveSimpleName("UnderstandingService")
                    .should().resideInAPackage("io.github.imzmq.interview.routing.application.contract");

    @Test
    void legacy_cross_module_persistence_allowlist_is_permanently_empty() {
        assertThat(LegacyCrossModulePersistenceDependencies.keys())
                .as("M8 forbids reintroducing historical cross-module persistence exceptions")
                .isEmpty();
    }

    @Test
    void legacy_cross_module_persistence_allowlist_matches_real_dependencies() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ModuleBoundaryRules.ROOT_PACKAGE);
        Set<ModuleBoundaryRules.DependencyKey> actual =
                ModuleBoundaryRules.findCrossModulePersistenceDependencies(productionClasses);

        assertThat(actual)
                .as("Remove an allowlist entry in the same commit that removes its production dependency")
                .containsAll(LegacyCrossModulePersistenceDependencies.keys());
    }

    @Test
    void cross_module_application_rule_rejects_foreign_implementation_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ForeignImplementationConsumer.class,
                InternalApplicationService.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .onlyAccessOtherModulesThroughApplicationContracts(Set.of(
                        "architecturefixturesource", "architecturefixturetarget"
                ))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> {
                    assertThat(detail).contains(ForeignImplementationConsumer.class.getName());
                    assertThat(detail).contains(InternalApplicationService.class.getName());
                });
    }

    @Test
    void cross_module_application_rule_accepts_provider_contract_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ForeignContractConsumer.class,
                PublicOperation.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .onlyAccessOtherModulesThroughApplicationContracts(Set.of(
                        "architecturefixturesource", "architecturefixturetarget"
                ))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void cross_module_application_rule_accepts_same_module_implementation_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                SameModuleInternalConsumer.class,
                LocalApplicationService.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .onlyAccessOtherModulesThroughApplicationContracts(Set.of(
                        "architecturefixturesource", "architecturefixturetarget"
                ))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void module_cycle_rule_rejects_contract_cycle() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ForeignContractConsumer.class,
                PublicOperation.class,
                ReverseContractConsumer.class,
                SourcePublicOperation.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .topLevelModulesShouldBeAcyclic(Set.of(
                        "architecturefixturesource", "architecturefixturetarget"
                ))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> assertThat(detail).contains(
                        "architecturefixturesource -> architecturefixturetarget -> architecturefixturesource"
                ));
    }

    @Test
    void module_cycle_rule_accepts_acyclic_contract_graph() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ForeignContractConsumer.class,
                PublicOperation.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .topLevelModulesShouldBeAcyclic(Set.of(
                        "architecturefixturesource", "architecturefixturetarget"
                ))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void cross_module_persistence_rule_rejects_unlisted_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                CrossModulePersistenceConsumer.class,
                ForeignPersistenceDO.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of())
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> {
                    assertThat(detail).contains(CrossModulePersistenceConsumer.class.getName());
                    assertThat(detail).contains(ForeignPersistenceDO.class.getName());
                });
    }

    @Test
    void cross_module_persistence_rule_allows_same_module_persistence() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ApplicationServiceDependingOnMapper.class,
                SamplePersistenceMapper.class,
                SamplePersistenceDO.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of())
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void cross_module_persistence_rule_accepts_exact_allowlisted_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                CrossModulePersistenceConsumer.class,
                ForeignPersistenceDO.class
        );
        ModuleBoundaryRules.DependencyKey allowed = new ModuleBoundaryRules.DependencyKey(
                CrossModulePersistenceConsumer.class.getName(),
                ForeignPersistenceDO.class.getName()
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of(allowed))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    private static DescribedPredicate<JavaClass> areMyBatisMappers() {
        return DescribedPredicate.describe(
                "MyBatis mapper interfaces",
                javaClass -> javaClass.isAssignableTo(BaseMapper.class)
                        || javaClass.isAnnotatedWith(Mapper.class)
                        || isConventionallyNamedPersistenceMapper(javaClass)
        );
    }

    private static boolean isConventionallyNamedPersistenceMapper(JavaClass javaClass) {
        return javaClass.isInterface()
                && javaClass.getSimpleName().endsWith("Mapper")
                && javaClass.getPackageName().contains(".infrastructure.persistence");
    }

    private static JavaClasses importRuleFixtureClasses() {
        return new ClassFileImporter().importClasses(
                ControllerDependingOnMapper.class,
                ApplicationServiceDependingOnMapper.class,
                SamplePersistenceMapper.class,
                SamplePersistenceDO.class
        );
    }

    private static ArchCondition<JavaClass> exist() {
        return new ArchCondition<>("exist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                events.add(SimpleConditionEvent.satisfied(item, item.getName() + " exists"));
            }
        };
    }
}
