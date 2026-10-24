package io.github.imzmq.interview.architecture;

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
    static final ArchRule agent_should_not_depend_on_controller =
            noClasses().that().resideInAnyPackage("..agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..api..");

    @ArchTest
    static final ArchRule controller_should_not_depend_on_tool_runtime_implementations =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..tool.runtime..");

    @ArchTest
    static final ArchRule entity_should_not_depend_on_service_or_controller =
            noClasses().that().resideInAnyPackage("..entity..")
                    .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..api..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule main_code_should_not_use_retired_top_level_entity_mapper_or_dto_packages =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.entity..",
                            "io.github.imzmq.interview.mapper..",
                            "io.github.imzmq.interview.dto..",
                            "io.github.imzmq.interview.stream..",
                            "io.github.imzmq.interview.intent..",
                            "io.github.imzmq.interview.turn.."
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
    static final ArchRule generic_chatstream_types_should_not_remain_owned_by_knowledge =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.knowledge.application.chatstream..")
                    .should(exist())
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule business_chat_handlers_should_only_use_chat_public_contracts =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application.chat..",
                            "io.github.imzmq.interview.knowledge.application.chat..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.github.imzmq.interview.chat.application");

    @ArchTest
    static final ArchRule chat_should_not_depend_on_concrete_scenario_handlers =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.interview.application.chat..",
                            "io.github.imzmq.interview.knowledge.application.chat..");

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
    static final ArchRule chat_contracts_should_not_expose_technical_or_persistence_types =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.chat.application.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.imzmq.interview.chat.infrastructure..",
                            "org.springframework.web..",
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
