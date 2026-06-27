package io.github.imzmq.interview.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "io.github.imzmq.interview",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controller_should_not_access_mapper_directly =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..mapper..");

    @ArchTest
    static final ArchRule agent_should_not_depend_on_controller =
            noClasses().that().resideInAnyPackage("..agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..api..");

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

    private static ArchCondition<JavaClass> exist() {
        return new ArchCondition<>("exist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                events.add(SimpleConditionEvent.satisfied(item, item.getName() + " exists"));
            }
        };
    }
}
