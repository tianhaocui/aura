package io.aura;

import io.aura.contract.CancellationToken;
import io.aura.contract.HealthStatus;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class RuntimeFoundationTest {
    @Test void strictConfigRejectsUnboundedAndInvalidValues() {
        assertThatThrownBy(() -> Aura.create(new AuraConfig().maxBodySize(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aura.create(new AuraConfig().maxUploadSize(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuraConfig().pageSizeMax(0).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuraConfig().workers(0).validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void createAcceptsCentralConfigAndLegacyCreateRemainsAvailable() {
        assertThat(Aura.create(new AuraConfig().port(9090).workers(8)).port()).isEqualTo(9090);
        assertThat(Aura.create().port()).isEqualTo(8080);
    }

    @Test
    void legacyAuraBuilderIsExplicitlyDeprecatedForRemoval() {
        Deprecated deprecated = Aura.class.getAnnotation(Deprecated.class);
        assertThat(deprecated).isNotNull();
        assertThat(deprecated.forRemoval()).isTrue();
        assertThat(deprecated.since()).isEqualTo("1.0.0");
    }

    @Test void runtimeContractExposesStableStateAndCapabilityLookup() {
        AuraRuntime runtime = Aura.create();
        assertThat(runtime.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
        assertThat(runtime.find(String.class)).isEmpty();
        assertThat(runtime.config()).isNotNull();
        runtime.close();
        assertThat(runtime.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
    }

    @Test void runtimeViewReportsRunningStateWithoutLegacyLifecycleType() {
        AuraApp app = AuraApp.create();
        assertThat(app.runtimeView()).isInstanceOf(AuraRuntime.class);
        assertThat(app.require(AuraRuntime.class)).isSameAs(app.runtimeView());
        assertThat(app.isRunning()).isFalse();
        app.close();
        assertThat(app.runtimeView().isRunning()).isFalse();
    }

    @Test void modularAppCanBeCreatedFromDeploymentPropertiesBeforeFreezing() {
        Properties properties = new Properties();
        properties.setProperty("aura.port", "9192");
        AuraApp app = AuraApp.create(properties);
        assertThat(app.config().port()).isEqualTo(9192);
        assertThatThrownBy(() -> app.start("--config=ignored.properties"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AuraApp.create");
    }

    @Test void cancellationIsAtomicAndObservable() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
        assertThat(token.cancel()).isTrue();
        assertThat(token.cancel()).isFalse();
        assertThatThrownBy(token::throwIfCancelled).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test void modularFacadeExposesStableAuthenticationConfiguration() {
        AuraApp app = AuraApp.create().authenticate(ctx -> "user-1");
        AuraWebRuntime runtime = (AuraWebRuntime) app.runtimeView();
        assertThat(runtime.authFunction()).isNotNull();
        assertThat(runtime.authFunction().apply(null)).isEqualTo("user-1");
    }

    @Test void modularFacadeExposesTransportNeutralMcpSecurityConfiguration() {
        AuraApp app = AuraApp.create()
                .mcpAuthenticate(metadata -> metadata.get("authorization"))
                .mcpAuthorize((user, permission) -> "service-42".equals(user)
                        && "orders.read".equals(permission));
        AuraMcpRuntime runtime = (AuraMcpRuntime) app.runtimeView();

        assertThat(runtime.mcpAuthenticator().authenticate(java.util.Map.of("authorization", "service-42")))
                .isEqualTo("service-42");
        assertThat(runtime.mcpAuthorizer().authorize("service-42", "orders.read")).isTrue();
    }

    @Test void lifecycleStopIsIdempotentAndTerminal() {
        Aura app = Aura.create();
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.NEW);
        app.stop(); app.stop();
        assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
        assertThatThrownBy(app::start).isInstanceOf(IllegalStateException.class);
    }

    @Test void healthChecksFailClosedWithoutExposingFailure() {
        Aura app = Aura.create().healthCheck(() -> { throw new IllegalStateException("password=secret"); });
        assertThat(app.healthStatus()).isEqualTo(HealthStatus.DOWN);
    }

    @Test void modularRuntimeExposesHealthChecksThroughStableWebContract() {
        AuraApp app = AuraApp.create()
                .healthCheck(() -> HealthStatus.UP)
                .healthCheck(() -> { throw new IllegalStateException("secret"); });
        AuraWebRuntime runtime = (AuraWebRuntime) app.runtimeView();

        assertThat(runtime.healthChecks()).hasSize(2);
        assertThat(runtime.healthStatus()).isEqualTo(HealthStatus.DOWN);
    }

    @Test void optionalRuntimeControlsDefaultOffAndTlsFailsFast() {
        Aura app = Aura.create();
        assertThat(app.rateLimitPerSecond()).isZero();
        assertThat(app.concurrencyLimit()).isZero();
        assertThatThrownBy(() -> new AuraConfig().tls("missing.p12", "").validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void modularRuntimeReadsWebSettingsFromCentralConfig() {
        AuraConfig config = new AuraConfig()
                .staticFiles("public")
                .spa(true)
                .cors(cors -> cors.origins("https://client.example").credentials(true))
                .accessLog("json")
                .gzip(true)
                .gzipMinSize(2048);
        AuraApp app = AuraApp.create(config);
        AuraWebRuntime runtime = (AuraWebRuntime) app.runtimeView();

        assertThat(runtime.staticFilesPath()).isEqualTo("public");
        assertThat(runtime.spa()).isTrue();
        assertThat(runtime.corsConfig().resolveOrigin("https://client.example"))
                .isEqualTo("https://client.example");
        assertThat(runtime.accessLogFormat()).isEqualTo("json");
        assertThat(runtime.gzip()).isTrue();
        assertThat(runtime.gzipMinSize()).isEqualTo(2048);
    }

    @Test void modularRuntimeFreezesBuilderAndNestedConfiguration() {
        AuraConfig source = new AuraConfig()
                .port(8081)
                .cors(cors -> cors.origins("https://client.example").credentials(true))
                .jsonConfig(json -> json.dateFormat("yyyy-MM-dd"))
                .snapshot(snapshot -> snapshot.maxBodySize(4096));
        AuraApp app = AuraApp.create(source);

        source.port(9090);
        source.corsConfig().origins("https://attacker.example");
        source.jsonConfig().dateFormat("MM-dd");
        source.snapshotOptions().maxBodySize(1);

        assertThat(app.config().port()).isEqualTo(8081);
        assertThat(app.config().corsConfig().resolveOrigin("https://client.example"))
                .isEqualTo("https://client.example");
        assertThat(app.config().jsonConfig().dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(app.config().snapshotOptions().maxBodySize()).isEqualTo(4096);

        app.config().corsConfig().origins("https://another.example");
        app.config().jsonConfig().dateFormat("MM-dd");
        app.config().snapshotOptions().maxBodySize(2);

        assertThat(app.config().corsConfig().resolveOrigin("https://client.example"))
                .isEqualTo("https://client.example");
        assertThat(app.config().jsonConfig().dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(app.config().snapshotOptions().maxBodySize()).isEqualTo(4096);
    }

    @Test void modularRuntimeRejectsPropertyChangesAfterPreparation() {
        AuraApp app = AuraApp.create().property("aura.orders.timeout", "5");
        app.prepare();

        assertThatThrownBy(() -> app.property("aura.orders.timeout", "10"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before module preparation");
    }

    @Test void healthModuleDeclaresTheStandardHealthRoutes() {
        AuraApp app = AuraApp.create().use(HealthModule.create());
        app.prepare();

        assertThat(app.modules()).extracting("id").containsExactly("health");
        assertThat(app.find(ModularRuntime.class)).isPresent();
        assertThat(((AuraWebRuntime) app.runtimeView()).directRoutes())
                .extracting(RouteEntry::method, RouteEntry::path)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("GET", "/health/live"),
                        org.assertj.core.api.Assertions.tuple("GET", "/health/ready"),
                        org.assertj.core.api.Assertions.tuple("GET", "/health"));
    }
}
