package io.aura.mcp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The copy of R6 that can actually fire.
 *
 * <p>{@code ArchitectureTest} in {@code aura-core} forbids {@code io.aura.mcp..} from reaching
 * into the Web adapter, but no {@code io.aura.mcp} class is on that module's classpath, so the
 * rule was vacuous there and said so. This module is where the subject set exists: the bridge is
 * a sibling mechanism of the adapter -- it talks to a running application over HTTP -- so nothing
 * in {@code src/main} has a reason to know about {@code io.aura.web} or Undertow. The tests do
 * mount an application, which is why {@code aura-web} is a test-scoped dependency here and the
 * import option excludes test classes rather than the rule being relaxed.
 *
 * <p>No {@code allowEmptyShould}: an empty subject set would mean the move that made this rule
 * meaningful had not happened, and a green build would be a lie.
 */
@AnalyzeClasses(
        packages = "io.aura",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class McpLayeringTest {

    @ArchTest
    static final ArchRule mcpDoesNotReachIntoTheWebAdapter =
            noClasses().that().resideInAPackage("io.aura.mcp..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.aura.web..", "io.undertow..")
                    .because("mcp is a sibling mechanism of web, not a consumer of its internals");
}
