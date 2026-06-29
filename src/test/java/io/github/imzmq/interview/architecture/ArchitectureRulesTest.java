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
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

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
                            "io.github.imzmq.interview.dto.."
                    )
                    .should(exist())
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule main_code_should_not_use_retired_top_level_stream_package =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.stream..")
                    .should(exist())
                    .allowEmptyShould(true);

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
