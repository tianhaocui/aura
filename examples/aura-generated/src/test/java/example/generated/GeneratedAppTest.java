package example.generated;

import io.aura.web.TestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedAppTest {
    @Test
    void generatedRoutesRunThroughTheStandardTestClientContract() {
        TestClient client = TestClient.of(GeneratedApp.create());

        assertThat(client.get("/generated/orders/1").expect(200).body())
                .contains("ready");
        assertThat(client.post("/generated/orders")
                .body(new GeneratedOrderService.CreateOrder(2L, "created"))
                .expect(200).body()).contains("created");
        assertThat(client.get("/generated/orders/2").expect(200).body())
                .contains("created");
        assertThat(client.schema().toString())
                .contains("Get one generated order", "Order identifier", "/generated/orders/{id}");
    }
}
