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
 *       {@code io.aura.snapshot}, plus the port abstractions in {@code io.aura.port}) and
 *       {@code aura-contract} ({@code io.aura.contract}). Defines WHAT an app is.</li>
 *   <li><b>Mechanism</b> — {@code aura-web} (Undertow), {@code aura-db} (HikariCP),
 *       {@code aura-mcp}, {@code aura-ai}, {@code aura-workflow}, {@code aura-processor}.
 *       Defines HOW. Policy must never depend on a mechanism, in either the Aura package
 *       space or third-party framework libraries.</li>
 * </ul>
 *
 * <p><b>Policy ports live in {@code io.aura.port}.</b> They used to sit in
 * {@code io.aura.web} alongside the Undertow implementation, which made the package name mean
 * two opposite things at once: {@code BaseHandler} was policy and
 * {@code CompiledWsRoute} was mechanism, and R1 could not forbid
 * {@code io.aura.web..} without banning the ports themselves. The split also leaked into the
 * code -- {@code WsHandler}'s callback fields were package-private and reachable from
 * {@code aura-web} only because the two modules shared the package name.
 *
 * <p>What the rename buys is expressible here: R1 now forbids {@code io.aura.web..} as a
 * dependency target for every policy package. It is a tripwire rather than the primary
 * guarantee, because {@code aura-core} has no compile dependency on {@code aura-web} at all --
 * the real bar is Maven's, and this rule catches the drift that Maven allows: a policy type
 * left in, or reaching into, the adapter's package in a module that sees both.
 *
 * <p>The layering rules that need another module's classpath live in that module:
 * {@code aura-web/src/test/java/io/aura/web/WebLayeringTest.java} and
 * {@code aura-mcp/src/test/java/io/aura/mcp/McpLayeringTest.java}. A rule kept here whose subject
 * set is not on this classpath passes by checking nothing, so R1's {@code io.aura.web..} entry is
 * deliberately a tripwire and R6 is not restated in this file at all.
 */
@AnalyzeClasses(
        packages = "io.aura",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    /**
     * Policy packages, i.e. everything that must stay mechanism-free. {@code io.aura.port} is
     * the vocabulary the adapter implements; it carries no mechanism with it.
     */
    private static final String[] POLICY_PACKAGES = {
            "io.aura",
            "io.aura.annotation..",
            "io.aura.snapshot..",
            "io.aura.contract..",
            "io.aura.port..",
    };

    /** Aura mechanism packages (module implementation side of the layering). */
    private static final String[] MECHANISM_PACKAGES = {
            "io.aura.web..",
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
     * R1: policy (core kernel + contract + ports) must not depend on any Aura mechanism module.
     * {@code io.aura.web..} joined the forbidden list when the ports moved to
     * {@code io.aura.port}, so the package name now means one thing: mechanism.
     */
    @ArchTest
    static final ArchRule coreIsIndependentOfMechanisms =
            noClasses().that().resideInAnyPackage(POLICY_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(MECHANISM_PACKAGES)
                    .because("Policy layer (core/contract/ports) must not depend on mechanism implementations");

    /**
     * R2: policy must stay on pure JDK + slf4j-api; framework libraries live in mechanism
     * modules only. The subject covers {@code io.aura.port}, whose classes used to hide inside
     * the adapter's package name. No {@code allowEmptyShould}: if the port package goes away
     * this rule has stopped checking something, and that is a failure, not a pass.
     */
    @ArchTest
    static final ArchRule coreHasNoFrameworkImports =
            noClasses().that()
                    .resideInAnyPackage("io.aura", "io.aura.annotation..", "io.aura.snapshot..",
                            "io.aura.contract..", "io.aura.port..")
                    .should().dependOnClassesThat().resideInAnyPackage(MECHANISM_LIBRARIES)
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
     * Stage ④ resolved the flagged violation by moving the built-in health module into
     * the Web adapter, so this is an enforced boundary now: a concrete module in core
     * would mean the policy layer shipping its own transport assumptions.
     */
    @ArchTest
    static final ArchRule noConcreteModulesInCore =
            noClasses().that().resideInAnyPackage("io.aura", "io.aura.annotation..", "io.aura.snapshot..")
                    .should().implement(AuraModule.class)
                    .because("Core declares AuraModule; only mechanisms/examples implement it");

    // R6 (mcp must not depend on web) lives in aura-mcp/src/test/java/io/aura/mcp/McpLayeringTest.java.
    // It cannot be enforced here: no io.aura.mcp class is on this module's classpath, so the rule
    // would have an empty subject set and pass by doing nothing -- which is what it did until the
    // copy in aura-mcp replaced it.
}
