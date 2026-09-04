package io.aura.web;

import io.aura.AuraApp;
import io.aura.HealthModule;
import io.aura.contract.HealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthModuleTest {
    @Test
    void standardHealthRoutesUseRuntimeStateAndFailClosedChecks() {
        AuraApp app = AuraApp.create()
                .healthCheck(() -> HealthStatus.DOWN)
                .use(HealthModule.create());
        TestClient client = TestClient.of(app);

        assertThat(client.get("/health/live").expect(200).body()).contains("\"UP\"");
        assertThat(client.get("/health/ready").expect(503).body()).contains("\"DOWN\"");
        assertThat(client.get("/health").expect(503).body()).contains("\"DOWN\"");
    }
}
