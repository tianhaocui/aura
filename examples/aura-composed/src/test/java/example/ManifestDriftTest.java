package example;

import io.aura.AuraApp;
import io.aura.capability.CapabilityRegistry;
import io.aura.contract.CapabilityDefinition;
import io.aura.manifest.ApplicationManifest;
import io.aura.mcp.McpRouter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The committed manifest is the declaration side; the runtime aggregation is the other. They are
 * reconciled here without a listener, and the MCP projection is reconciled against the capability
 * definitions themselves. {@code AURA_MANIFEST_UPDATE=true} deliberately rewrites the committed
 * file after an intentional composition change — the same doctrine as {@code AURA_API_UPDATE}.
 */
class ManifestDriftTest {
    private static final Path COMMITTED = Path.of("src/main/resources/manifest.json");

    private static Map<String, Object> actualManifest() {
        AuraApp app = ComposedApp.create();
        app.prepare();
        return ApplicationManifest.generate(app.runtimeView());
    }

    @Test
    @SuppressWarnings("unchecked")
    void committedManifestMatchesTheComposition() throws IOException {
        Map<String, Object> actual = actualManifest();
        String rendered = ApplicationManifest.toJson(actual);
        if ("true".equals(System.getenv("AURA_MANIFEST_UPDATE"))) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, rendered);
            return;
        }

        assertThat(Files.exists(COMMITTED))
                .as("committed manifest missing: run AURA_MANIFEST_UPDATE=true mvn -pl examples/aura-composed test")
                .isTrue();
        Map<String, Object> declared = ApplicationManifest.fromJson(Files.readString(COMMITTED));
        assertThat(ApplicationManifest.reconcile(declared, actual)).isEmpty();
        assertThat(Files.readString(COMMITTED)).isEqualTo(rendered);

        assertThat(((Number) actual.get("schemaVersion")).intValue())
                .isEqualTo(ApplicationManifest.SCHEMA_VERSION);
        assertThat(actual.keySet())
                .containsExactly("schemaVersion", "modules", "capabilities", "permissions");
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) actual.get("capabilities");
        assertThat(capabilities).singleElement().satisfies(capability -> {
            assertThat(capability.get("name")).isEqualTo("order.query");
            assertThat((Map<String, Object>) capability.get("access"))
                    .containsEntry("policy", "PERMISSION")
                    .containsEntry("permission", "order.read");
            assertThat((List<Map<String, Object>>) capability.get("params"))
                    .singleElement()
                    .satisfies(param -> assertThat(param.get("name")).isEqualTo("id"));
        });
        assertThat((List<Map<String, Object>>) actual.get("permissions"))
                .singleElement()
                .satisfies(permission -> assertThat(permission.get("id")).isEqualTo("order.read"));
    }

    @Test
    void mcpProjectionReconcilesAgainstTheDefinitions() {
        AuraApp app = ComposedApp.create();
        app.prepare();
        CapabilityRegistry registry = app.require(CapabilityRegistry.class);
        McpRouter router = new McpRouter();
        for (CapabilityDefinition definition : registry.snapshot().values()) {
            router.tool(definition);
        }

        assertThat(router.capabilityBindings()).containsExactlyEntriesOf(
                Map.of("order_query", "order.query"));
        assertThatCode(() -> router.reconcileCapabilities(registry)).doesNotThrowAnyException();
    }

    @Test
    void optInPrepareCheckAcceptsTheCommittedManifest() {
        AuraApp app = ComposedApp.create().property("aura.manifest", "manifest.json");
        assertThatCode(app::prepare).doesNotThrowAnyException();
        assertThatCode(app::prepare).doesNotThrowAnyException();
    }
}