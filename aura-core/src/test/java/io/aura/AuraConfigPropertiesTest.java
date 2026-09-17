package io.aura;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuraConfigPropertiesTest {
    @Test
    void loadsFrameworkSettingsAndValidatesThemBeforeSnapshot() {
        Properties properties = new Properties();
        properties.setProperty("aura.port", "9191");
        properties.setProperty("aura.workers", "12");
        properties.setProperty("aura.max-body-size", "8192");
        properties.setProperty("aura.max-upload-size", "4096");
        properties.setProperty("aura.rate-limit-max-keys", "1234");
        properties.setProperty("aura.allowed-hosts", "api.example, admin.example ");
        properties.setProperty("aura.trusted-proxies", "127.0.0.1, 10.0.0.0/8");
        properties.setProperty("aura.openapi", "true");
        properties.setProperty("aura.dev-mode", "true");
        properties.setProperty("aura.orders.timeout", "5");

        AuraConfig config = AuraConfig.fromProperties(properties);
        AuraApp app = AuraApp.create(config);

        assertThat(app.config().port()).isEqualTo(9191);
        assertThat(app.config().workers()).isEqualTo(12);
        assertThat(app.config().maxBodySize()).isEqualTo(8192);
        assertThat(app.config().maxUploadSize()).isEqualTo(4096);
        assertThat(app.config().rateLimitMaxKeys()).isEqualTo(1234);
        assertThat(app.config().allowedHosts()).containsExactlyInAnyOrder("api.example", "admin.example");
        assertThat(app.config().trustedProxies()).containsExactlyInAnyOrder("127.0.0.1", "10.0.0.0/8");
        assertThat(app.config().openApiEnabled()).isTrue();
        assertThat(app.config().devMode()).isTrue();
        app.use(new AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("orders", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                assertThat(context.property("aura.orders.timeout")).isEqualTo("5");
            }
        }).prepare();
    }

    @Test
    void rejectsMalformedPropertiesWithThePropertyName() {
        Properties properties = new Properties();
        properties.setProperty("aura.workers", "many");

        assertThatThrownBy(() -> AuraConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aura.workers");
    }

    @Test
    void rejectsInvalidBooleanProperties() {
        Properties properties = new Properties();
        properties.setProperty("aura.openapi", "enabled");

        assertThatThrownBy(() -> AuraConfig.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aura.openapi");
    }

    @Test
    void rejectsHostnamesAndMalformedTrustedProxyCidrs() {
        assertThatThrownBy(() -> new AuraConfig().trustedProxies("proxy.internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric IP");
        assertThatThrownBy(() -> new AuraConfig().trustedProxies("10.0.0.0/99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid trusted proxy");
    }
}
