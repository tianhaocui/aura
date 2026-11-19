package io.aura.manifest;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.capability.CapabilityModule;
import io.aura.capability.CapabilityRegistry;
import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationManifestTest {

    private static AuraModule ordersModule() {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("orders", "1.0.0").requires("capability");
            }

            @Override public void install(ModuleContext context) {
                context.permissions().register("order.read", "Read orders");
                context.require(CapabilityRegistry.class).register(Capability.of(
                        "order.query", "Query an <order> & its status",
                        AccessPolicy.permission("order.read"),
                        (args, userId) -> "order-" + args.get("id"),
                        new ApiParameter("id", "long", "query", true, "Order ID")));
            }
        };
    }

    private static AuraApp composedApp() {
        return AuraApp.create().use(ordersModule()).use(CapabilityModule.create());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateAggregatesSortedDeclarationsAndNoRuntimeState() {
        AuraApp app = composedApp();
        app.prepare();

        Map<String, Object> manifest = ApplicationManifest.generate(app.runtimeView());
        assertThat(manifest.get("schemaVersion")).isEqualTo(1);
        assertThat(manifest.keySet()).containsExactly("schemaVersion", "modules", "capabilities", "permissions");

        List<Map<String, Object>> modules = (List<Map<String, Object>>) manifest.get("modules");
        assertThat(modules).extracting(entry -> entry.get("id")).containsExactly("capability", "orders");
        Map<String, Object> orders = modules.get(1);
        assertThat(orders.get("version")).isEqualTo("1.0.0");
        assertThat((List<Map<String, Object>>) orders.get("requires"))
                .singleElement().satisfies(requirement ->
                        assertThat(requirement.get("id")).isEqualTo("capability"));
        assertThat((List<String>) modules.get(0).get("provides")).containsExactly("capability.registry");

        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) manifest.get("capabilities");
        assertThat(capabilities).singleElement().satisfies(capability -> {
            assertThat(capability.get("name")).isEqualTo("order.query");
            assertThat(capability.get("access"))
                    .isEqualTo(Map.of("policy", "PERMISSION", "permission", "order.read"));
        });
        List<?> registryProjection = (List<?>) app.require(CapabilityRegistry.class)
                .schema().get("capabilities");
        assertThat(capabilities.get(0)).isEqualTo(registryProjection.get(0));

        assertThat((List<Map<String, Object>>) manifest.get("permissions"))
                .singleElement().satisfies(permission -> {
                    assertThat(permission.get("id")).isEqualTo("order.read");
                    assertThat(permission.get("description")).isEqualTo("Read orders");
                });
        app.close();
    }

    @Test
    void renderingIsDeterministicAndKeepsDescriptionsUnescaped() {
        AuraApp app = composedApp();
        app.prepare();
        String first = ApplicationManifest.toJson(ApplicationManifest.generate(app.runtimeView()));
        String second = ApplicationManifest.toJson(ApplicationManifest.generate(app.runtimeView()));

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("Query an <order> & its status");
        app.close();
    }

    @Test
    void jsonRoundTripReconcilesCleanDespiteParsedNumbersBeingDoubles() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> actual = ApplicationManifest.generate(app.runtimeView());
        Map<String, Object> declared = ApplicationManifest.fromJson(ApplicationManifest.toJson(actual));

        assertThat(declared.get("schemaVersion")).isInstanceOf(Double.class);
        assertThat(ApplicationManifest.reconcile(declared, actual)).isEmpty();
        app.close();
    }

    @Test
    void aMissingModuleDeclarationIsReportedByName() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        modules(declared).removeIf(entry -> "orders".equals(entry.get("id")));

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("module 'orders'").contains("registered but not declared"));
        app.close();
    }

    @Test
    void aModuleDeclaredButNotInstalledIsReportedByName() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        modules(declared).add(Map.of("id", "ghost", "version", "1.0.0",
                "requires", List.of(), "provides", List.of()));

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("module 'ghost'").contains("declared but not registered"));
        app.close();
    }

    @Test
    void anUndeclaredCapabilityRegistrationIsReportedByName() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        capabilities(declared).clear();

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("capability 'order.query'").contains("registered but not declared"));
        app.close();
    }

    @Test
    void aChangedParameterSchemaIsReportedByFieldPath() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        Map<String, Object> param = params(capabilities(declared).get(0)).get(0);
        param.put("type", "String");

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("capability 'order.query'").contains("params[0]/type"));
        app.close();
    }

    @Test
    void aChangedPermissionPolicyIsReported() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        capabilities(declared).get(0).put("access", Map.of("policy", "PUBLIC"));

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("capability 'order.query'").contains("access"));
        app.close();
    }

    @Test
    void anUndeclaredPermissionIsReportedByName() {
        AuraApp app = composedApp();
        app.prepare();
        Map<String, Object> declared = roundTrip(app);
        permissions(declared).add(Map.of("id", "order.refund"));

        assertThat(ApplicationManifest.reconcile(declared, ApplicationManifest.generate(app.runtimeView())))
                .anySatisfy(difference -> assertThat(difference)
                        .contains("permission 'order.refund'").contains("declared but not registered"));
        app.close();
    }

    private static Map<String, Object> roundTrip(AuraApp app) {
        return ApplicationManifest.fromJson(
                ApplicationManifest.toJson(ApplicationManifest.generate(app.runtimeView())));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modules(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("modules");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> capabilities(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("capabilities");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> params(Map<String, Object> capability) {
        return (List<Map<String, Object>>) capability.get("params");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> permissions(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("permissions");
    }
}