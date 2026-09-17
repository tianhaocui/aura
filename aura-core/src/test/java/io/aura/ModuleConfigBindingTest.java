package io.aura;

import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleConfigBindingTest {

    record ApiConfig(String key, String baseUrl, long timeout, boolean debug,
                     double rate, Integer maxConnections, Boolean enabled) { }

    @Test
    void bindsSupportedTypesFromTheCurrentModuleNamespace() {
        AtomicReference<ApiConfig> seen = new AtomicReference<>();
        AuraApp app = AuraApp.create()
                .property("aura.api.key", "secret123")
                .property("aura.api.baseUrl", "https://example.com")
                .property("aura.api.timeout", "5000")
                .property("aura.api.debug", "true")
                .property("aura.api.rate", "1.5")
                .use(module("api", context -> seen.set(context.config(ApiConfig.class))));

        app.prepare();

        assertThat(seen.get()).isEqualTo(new ApiConfig(
                "secret123", "https://example.com", 5000L, true, 1.5, null, null));
    }

    @Test
    void systemPropertiesOverrideApplicationProperties() {
        String key = "aura.api.timeout";
        String previous = System.getProperty(key);
        System.setProperty(key, "37");
        try {
            AtomicReference<ApiConfig> seen = new AtomicReference<>();
            AuraApp.create()
                    .property(key, "12")
                    .use(module("api", context -> seen.set(context.config(ApiConfig.class))))
                    .prepare();
            assertThat(seen.get().timeout()).isEqualTo(37L);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void rejectsNonRecordConfigurationTypes() {
        AuraApp app = AuraApp.create().use(module("api", context -> context.config(String.class)));

        assertThatThrownBy(app::prepare)
                .isInstanceOf(ModuleException.class)
                .extracting("code").isEqualTo("AURA_MODULE_CONFIG_TYPE");
    }

    private static AuraModule module(String id, java.util.function.Consumer<ModuleContext> install) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(id, "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                install.accept(context);
            }
        };
    }
}
