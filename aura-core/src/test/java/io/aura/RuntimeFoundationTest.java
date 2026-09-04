package io.aura;

import io.aura.contract.CancellationToken;
import io.aura.contract.HealthStatus;

import org.junit.jupiter.api.Test;

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
        assertThat(app.isRunning()).isFalse();
        app.close();
        assertThat(app.runtimeView().isRunning()).isFalse();
    }

    @Test void cancellationIsAtomicAndObservable() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
        assertThat(token.cancel()).isTrue();
        assertThat(token.cancel()).isFalse();
        assertThatThrownBy(token::throwIfCancelled).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test void lifecycleStopIsIdempotentAndTerminal() {
        Aura app = Aura.create();
        assertThat(app.lifecycle()).isEqualTo(Aura.Lifecycle.NEW);
        app.stop(); app.stop();
        assertThat(app.lifecycle()).isEqualTo(Aura.Lifecycle.STOPPED);
        assertThatThrownBy(app::start).isInstanceOf(IllegalStateException.class);
    }

    @Test void healthChecksFailClosedWithoutExposingFailure() {
        Aura app = Aura.create().healthCheck(() -> { throw new IllegalStateException("password=secret"); });
        assertThat(app.healthStatus()).isEqualTo(HealthStatus.DOWN);
    }

    @Test void optionalRuntimeControlsDefaultOffAndTlsFailsFast() {
        Aura app = Aura.create();
        assertThat(app.rateLimitPerSecond()).isZero();
        assertThat(app.concurrencyLimit()).isZero();
        assertThatThrownBy(() -> new AuraConfig().tls("missing.p12", "").validate()).isInstanceOf(IllegalArgumentException.class);
    }
}
