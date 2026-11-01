package io.aura;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Stage ① mechanical enforcement of the "Policy in Core / Mechanism in Web" rule.
 *
 * <p>Aura 1.0 layering contract:
 * <ul>
 *   <li><b>Policy</b> — {@code aura-core} ({@code io.aura}, {@code io.aura.annotation},
 *       {@code io.aura.snapshot}, plus the port abstractions in {@code io.aura.web}) and
 *       {@code aura-contract} ({@code io.aura.contract}). Defines WHAT an app is.</li>
 *   <li><b>Mechanism</b> — {@code aura-web} (Undertow), {@code aura-db} (HikariCP),
 *       {@code aura-mcp}, {@code aura-ai}, {@code aura-workflow}, {@code aura-processor}.
 *       Defines HOW. Policy must never depend on a mechanism, in either the Aura package
 *       space or third-party framework libraries.</li>
 * </ul>
 *
 * <p><b>Known split package (Stage ② cleanup):</b> {@code io.aura.web} exists in BOTH
 * {@code aura-core/src/main/java/io/aura/web/} (port abstractions: BaseRouter, BaseContext,
 * BaseHandler, SseEmitter, UploadedFile, ...) and {@code aura-web/src/main/java/io/aura/web/}
 * (Undertow implementation). This is a JPMS violation and means ArchUnit cannot distinguish
 * core ports from web mechanisms by package name alone. Consequently R1 deliberately does
 * NOT list {@code io.aura.web..} as a forbidden target (it would ban the ports themselves);
 * the Undertow implementation is instead covered by R2's third-party ban, and this test runs
 * in aura-core where only the port classes are on the classpath.
 * Stage ② plan: rename the core ports to {@code io.aura.port..} (or move them into
 * {@code io.aura.contract}) and then tighten R1 to also forbid {@code io.aura.web..} as a
 * dependency target:
 * <pre>
 * TODO Stage②: noClasses().that().resideInAnyPackage(POLICY_PACKAGES)
 *     .should().dependOnClassesThat().resideInAnyPackage("io.aura.web..", ...);
 * </pre>
 *
 * <p><b>Known violation flagged but not fixed here:</b> {@code aura-mcp} depends on
 * {@code aura-web} (reverse dependency, see {@code aura-mcp/pom.xml} — flagged by R6 below;
 * the rule is vacuous when run from aura-core because io.aura.mcp is not on this classpath,
 * it needs a sibling copy in an aura-mcp test to actually bite). The fix is a separate PR.
 */
@AnalyzeClasses(
        packages = "io.aura",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    /**
     * Policy packages, i.e. everything that must stay mechanism-free.
     * {@code io.aura.web} is excluded because it is a split package (see class javadoc).
     */
    private static final String[] POLICY_PACKAGES = {
            "io.aura",
            "io.aura.annotation..",
            "io.aura.snapshot..",
            "io.aura.contract..",
    };

    /** Aura mechanism packages (module implementation side of the layering). */
    private static final String[] MECHANISM_PACKAGES = {
            "io.aura.db..",
            "io.aura.mcp..",
            "io.aura.ai..",
            "io.aura.workflow..",
            "io.aura.processor..",
            // Guards for packages that do not exist yet but must not leak into policy:
            "io.aura.dev..",
            "io.aura.starter..",
    };

    /** Third-party libraries that only mechanism modules may touch. */
    private static final String[] MECHANISM_LIBRARIES = {
            "io.undertow..",
            "com.alibaba.fastjson2..",
            "com.zaxxer.hikari..",
            "com.fasterxml.jackson..",
            "org.flywaydb..",
            "liquibase..",
            "org.postgresql..",
            "com.mysql..",
            "org.jboss.logging..",
    };

    /**
     * R1: policy (core kernel + contract) must not depend on any Aura mechanism module.
     * Currently green — verified by grep: no core/contract source imports io.aura.db/mcp/ai/
     * workflow/processor/dev/starter.
     */
    @ArchTest
    static final ArchRule coreIsIndependentOfMechanisms =
            noClasses().that().resideInAnyPackage(POLICY_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(MECHANISM_PACKAGES)
                    .because("Policy layer (core/contract) must not depend on mechanism implementations");

    /**
     * R2: policy must stay on pure JDK + slf4j-api; framework libraries live in mechanism
     * modules only. Subject also covers the core {@code io.aura.web} ports, since on this
     * module's classpath every imported {@code io.aura.web} class IS a core port.
     * Currently green — JsonConfig/JwtSupport/snapshot are library-free (JwtSupport uses
     * {@code javax.crypto} from the JDK, which is allowed).
     */
    @ArchTest
    static final ArchRule coreHasNoFrameworkImports =
            noClasses().that()
                    .resideInAnyPackage("io.aura", "io.aura.annotation..", "io.aura.snapshot..",
                            "io.aura.contract..", "io.aura.web..")
                    .should().dependOnClassesThat().resideInAnyPackage(MECHANISM_LIBRARIES)
                    .allowEmptyShould(true)
                    .because("Core stays on pure JDK + slf4j-api; framework deps live in mechanism modules only");

    /**
     * R3: {@code io.aura.contract} is the shared vocabulary — contract-free of everything
     * except JDK types and primitives (no core, no slf4j, no frameworks).
     * Currently green — aura-contract/src/main imports only java.* / io.aura.contract.*.
     * NOTE: {@code ImportOption.DoNotIncludeTests.class} above excludes test classes from
     * the import; the empty package string {@code ""} covers primitives and array types.
     */
    @ArchTest
    static final ArchRule contractIsDependencyFree =
            classes().that().resideInAPackage("io.aura.contract..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("io.aura.contract..", "java..", "javax..", "")
                    .because("aura-contract is the zero-dependency shared vocabulary");

    // R4: SPI registration files (META-INF/services for AuraStarter / McpStarter) are a
    // filesystem check, not ArchUnit's job — intentionally skipped; cover it in a plain
    // unit test or the build scripts when needed.

    /**
     * R5: core declares {@link AuraModule}; only mechanisms/examples implement it.
     * KNOWN VIOLATION, intentionally left FAILING for the parent orchestrator to see:
     * {@code io.aura.HealthModule} is a concrete AuraModule implementation living in
     * aura-core. Stage ②/④ should move it (e.g. to aura-web or examples) or absorb it
     * into the simplified module API; do NOT @ArchIgnore this in Stage ①.
     */
    @ArchTest
    static final ArchRule noConcreteModulesInCore =
            noClasses().that().resideInAnyPackage("io.aura", "io.aura.annotation..", "io.aura.snapshot..")
                    .should().implement(AuraModule.class)
                    .as("Stage① known violation: io.aura.HealthModule implements AuraModule inside aura-core "
                            + "(core may declare AuraModule but must not implement it) — cleanup scheduled for Stage②/④")
                    .because("Core declares AuraModule; only mechanisms/examples implement it");

    /**
     * R6: flag the known {@code mcp → web} reverse dependency (aura-mcp/pom.xml lists a
     * compile dependency on aura-web). From aura-core this rule is vacuous — io.aura.mcp
     * classes are not on this classpath — hence {@code allowEmptyShould(true)}; it acts as
     * documentation now and becomes a real gate once copied into an aura-mcp test (separate
     * PR; do not fix the dependency here).
     */
    @ArchTest
    static final ArchRule mcpMustNotDependOnWeb =
            noClasses().that().resideInAPackage("io.aura.mcp..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.aura.web..", "io.undertow..")
                    .as("Stage① known bug flag: aura-mcp depends on aura-web (see aura-mcp/pom.xml) — "
                            + "enforced for real in a follow-up PR")
                    .allowEmptyShould(true)
                    .because("mcp is a sibling mechanism of web, not a consumer of its internals");
}
