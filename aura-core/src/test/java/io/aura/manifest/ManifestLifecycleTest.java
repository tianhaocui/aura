package io.aura.manifest;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.capability.CapabilityModule;
import io.aura.capability.CapabilityRegistry;
import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.ContractException;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The opt-in lifecycle check. Manifest fixtures are written into target/test-classes so the
 * runtime reads them from the classpath exactly as a committed application resource would.
 */
class ManifestLifecycleTest {

    private static AuraModule ordersModule(AtomicBoolean stopped) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("orders", "1.0.0").requires("capability");
            }

            @Override public void install(ModuleContext context) {
                context.require(CapabilityRegistry.class).register(Capability.of(
                        "order.query", "Query an order", AccessPolicy.permission("order.read"),
                        (args, userId) -> "order-" + args.get("id"),
                        new ApiParameter("id", "long", "query", true, "Order ID")));
            }

            @Override public void stop(ModuleContext context) {
                if (stopped != null) stopped.set(true);
            }
        };
    }

    private static AuraApp composedApp(AtomicBoolean stopped) {
        return AuraApp.create().use(ordersModule(stopped)).use(CapabilityModule.create());
    }

    /** Prepares one app and writes its manifest where the test classloader can read it. */
    private String writeManifest(String name, Map<String, Object> manifest) throws Exception {
        Path directory = Path.of("target", "test-classes", "manifest-fixtures");
        Files.createDirectories(directory);
        Path file = directory.resolve(name + ".json");
        Files.writeString(file, ApplicationManifest.toJson(manifest));
        return "manifest-fixtures/" + name + ".json";
    }

    private Map<String, Object> currentManifest() {
        AuraApp app = composedApp(null);
        app.prepare();
        Map<String, Object> manifest = ApplicationManifest.generate(app.runtimeView());
        app.close();
        return manifest;
    }

    @Test
    void aMatchingManifestPassesPrepareWithoutAListener() throws Exception {
        String resource = writeManifest("matching", currentManifest());
        AuraApp app = composedApp(null).property("aura.manifest", resource);
        app.prepare();
        app.prepare();

        assertThat(app.require(CapabilityRegistry.class).contains("order.query")).isTrue();
        app.close();
    }

    @Test
    void driftFailsPrepareAndRollsInstalledModulesBack() throws Exception {
        Map<String, Object> declared = currentManifest();
        ((List<?>) declared.get("capabilities")).clear();
        String resource = writeManifest("drift", declared);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AuraApp app = composedApp(stopped).property("aura.manifest", resource);

        assertThatThrownBy(app::prepare)
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MANIFEST_DRIFT");
                    assertThat(e.error().message()).contains("order.query");
                });
        assertThat(stopped.get()).isTrue();
    }

    @Test
    void aMissingResourceFailsClosed() {
        AuraApp app = composedApp(null).property("aura.manifest", "manifest-fixtures/absent.json");
        assertThatThrownBy(app::prepare)
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_MANIFEST_UNREADABLE"));
    }

    @Test
    void malformedJsonFailsClosed() throws Exception {
        Path directory = Path.of("target", "test-classes", "manifest-fixtures");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("malformed.json"), "{ not json");
        AuraApp app = composedApp(null).property("aura.manifest", "manifest-fixtures/malformed.json");

        assertThatThrownBy(app::prepare)
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_MANIFEST_UNREADABLE"));
    }

    @Test
    void withoutThePropertyNothingChanges() {
        AuraApp app = composedApp(null);
        app.prepare();
        assertThat(app.require(CapabilityRegistry.class).contains("order.query")).isTrue();
        app.close();
    }

    @Test
    void startReconcilesBeforeLookingForAStarter() throws Exception {
        Map<String, Object> declared = currentManifest();
        ((List<?>) declared.get("capabilities")).clear();
        String drifted = writeManifest("start-drift", declared);
        AuraApp app = composedApp(null).property("aura.manifest", drifted);

        // aura-core has no AuraStarter on its classpath: a drift failure must win over the
        // missing-starter failure, proving the check runs right after install on the start path.
        assertThatThrownBy(app::start)
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_MANIFEST_DRIFT"));
    }

    @Test
    void aMatchingManifestLetsStartReachTheStarterLookup() throws Exception {
        String resource = writeManifest("start-matching", currentManifest());
        AuraApp app = composedApp(null).property("aura.manifest", resource);

        assertThatThrownBy(app::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AuraStarter found");
    }
}