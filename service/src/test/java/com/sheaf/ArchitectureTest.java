package com.sheaf;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the domain/infrastructure boundary.
 *
 * <p>The domain layer is the heart of the project. It must remain pure Java 21 —
 * zero framework imports. This is what makes it testable, portable, and trustworthy.
 * Add a Spring import to {@code domain/} and the build fails here, not at code review.
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter().importPackages("com.sheaf");
    }

    @Test
    void domain_must_not_import_spring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sheaf.domain..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");

        rule.check(importedClasses);
    }

    @Test
    void domain_must_not_import_jakarta_persistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sheaf.domain..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

        rule.check(importedClasses);
    }

    @Test
    void adapters_must_not_import_domain_internals_directly() {
        // Adapters access the domain through application services, not by reaching
        // directly into domain sub-packages other than the public IR types.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sheaf.adapters..")
                .should().dependOnClassesThat().resideInAPackage("com.sheaf.domain.types..")
                .orShould().dependOnClassesThat().resideInAPackage("com.sheaf.domain.validation..")
                .orShould().dependOnClassesThat().resideInAPackage("com.sheaf.domain.analysis..");

        rule.check(importedClasses);
    }
}
