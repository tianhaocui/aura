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
        @SuppressWarnings("unchecked")
        var routes = (java.util.List<io.aura.contract.ApiRoute>) client.schema().get("routes");
        var create = routes.stream().filter(route -> route.path().equals("/generated/orders")
                && route.method().equals("POST")).findFirst().orElseThrow();
        assertThat(create.params()).singleElement()
                .satisfies(parameter -> {
                    assertThat(parameter.schema()).containsEntry("type", "object");
                    @SuppressWarnings("unchecked")
                    var properties = (java.util.Map<String, Object>) parameter.schema().get("properties");
                    @SuppressWarnings("unchecked")
                    var status = (java.util.Map<String, Object>) properties.get("status");
                    assertThat(status).containsEntry("minLength", 1);
                });
    }
}
