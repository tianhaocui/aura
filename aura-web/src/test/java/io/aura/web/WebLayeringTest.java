package io.aura.web;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The layering rule that only the adapter's own classpath can check.
 *
 * <p>{@code ArchitectureTest} runs in {@code aura-core}, where {@code io.aura.web} is not on the
 * classpath at all: its ban on the mechanism packages is a compile-scope guarantee restated, and
 * the {@code io.aura.web..} entry there cannot fire. Here the policy classes and the Undertow
 * implementation are both loaded, so the boundary is checked against the artifact users actually
 * get -- a policy type shipped inside the adapter, or a port that reached across it, fails this
 * rule instead of surviving because the two modules agreed on a package name.
 *
 * <p>No {@code allowEmptyShould}: if no policy class is imported any more, the check has stopped
 * covering anything, and a green build would be a lie.
 */
@AnalyzeClasses(
        packages = "io.aura",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class WebLayeringTest {

    private static final String[] POLICY_PACKAGES = {
            "io.aura",
            "io.aura.annotation..",
            "io.aura.snapshot..",
            "io.aura.contract..",
            "io.aura.port..",
    };

    @ArchTest
    static final ArchRule policyStaysOutOfTheAdapter =
            noClasses().that().resideInAnyPackage(POLICY_PACKAGES)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.aura.web..", "io.undertow..")
                    .because("io.aura.web is the Undertow mechanism; policy names the port it implements instead");
}
