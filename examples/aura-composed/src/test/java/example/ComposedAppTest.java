package example;

import io.aura.AuraApp;
import io.aura.web.TestClient;
import io.aura.workflow.WorkflowRegistry;
import io.aura.workflow.WorkflowRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComposedAppTest {
    @Test
    void composedBusinessModuleIsTestableWithoutStartingServer() {
        AuraApp app = ComposedApp.create();
        assertThat(TestClient.of(app).get("/orders").expect(200).body()).contains("ready");

        WorkflowRegistry registry = app.require(WorkflowRegistry.class);
        List<String> events = new ArrayList<>();
        assertThat(new WorkflowRuntime<List<String>>(registry)
                .execute("orders.fulfillment", events).succeeded()).isTrue();
        assertThat(events).containsExactly("place-order", "outbound");
    }
}
