package io.github.tony079.sdlc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Modular-monolith rules (TDD 6.1, ArchUnit ARCH-01..04 subset for Milestone 1). */
class ArchitectureTest {

    private static final String ROOT = "io.github.tony079.sdlc";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /** A module may use another module only through that module's api package (common is shared). */
    @Test
    void modulesDependOnEachOtherOnlyThroughApiPackages() {
        classes().that().resideInAPackage(ROOT + ".*..")
                .should(new ArchCondition<>("access other modules only via their api package") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        String own = module(origin);
                        origin.getDirectDependenciesFromSelf().forEach(dependency -> {
                            JavaClass target = dependency.getTargetClass();
                            String other = module(target);
                            if (other == null || other.equals(own) || other.equals("common")) {
                                return;
                            }
                            String apiPackage = ROOT + "." + other + ".api";
                            if (!target.getPackageName().equals(apiPackage)
                                    && !target.getPackageName().startsWith(apiPackage + ".")) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                            }
                        });
                    }
                })
                .check(CLASSES);
    }

    /** Controllers go through services; only services touch the data-access layer (@Repository classes). */
    @Test
    void controllersDoNotUseRepositoriesDirectly() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAnnotatedWith(Repository.class)
                .check(CLASSES);
    }

    @Test
    void onlyCommonTxConstructsTheTenantAwareTransactionManager() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".common.tx..")
                .should().dependOnClassesThat().haveSimpleName("TenantAwareTransactionManager")
                .check(CLASSES);
    }

    /** Scope guard (TDD 6.1 rule 6): no packages for later-release stages in Release 1. */
    @Test
    void noPackagesForOutOfScopeStages() {
        DescribedPredicate<JavaClass> outOfScope = DescribedPredicate.describe("out-of-scope stage package",
                c -> c.getPackageName().matches(ROOT.replace(".", "\\.")
                        + "\\.(estimation|scope|commercial|tdd|development|qa|pullrequest|deployment)(\\..*)?"));
        noClasses().should(new ArchCondition<>("reside in an out-of-scope stage package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (outOfScope.test(item)) {
                    events.add(SimpleConditionEvent.violated(item, item.getName()));
                }
            }
        }).check(CLASSES);
    }

    /**
     * The model gateway is a leaf: it cannot reach workflow, approvals, jobs, outbox, documents, storage, requirements
     * or identity, so no model output can change state through it (PRD section 11, TDD 13).
     */
    @Test
    void modelGatewayCannotReachStateChangingModules() {
        noClasses().that().resideInAPackage(ROOT + ".modelgateway..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".workflow..", ROOT + ".queue..",
                        ROOT + ".outbox..", ROOT + ".document..", ROOT + ".docprocessing..", ROOT + ".storage..",
                        ROOT + ".requirement..", ROOT + ".identity..", ROOT + ".audit..", ROOT + ".project..")
                .check(CLASSES);
    }

    private static String module(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        if (!name.startsWith(ROOT + ".")) {
            return null;
        }
        String rest = name.substring(ROOT.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
