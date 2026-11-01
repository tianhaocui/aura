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
        var get = routes.stream().filter(route -> route.path().equals("/generated/orders/{id}")
                && route.method().equals("GET")).findFirst().orElseThrow();
        assertThat(get.responseSchema()).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        var responseProperties = (java.util.Map<String, Object>) get.responseSchema().get("properties");
        assertThat(responseProperties.keySet()).contains("id", "status");
    }

    @Test
    void generatedRoutesBindEnumsAndDecimalsExactlyLikeTheReflectionPath() {
        TestClient client = TestClient.of(GeneratedApp.create());

        assertThat(client.get("/generated/orders/search")
                .query("direction", "UP").query("minId", "1")
                .expect(200).body()).contains("ready");

        var search = ((java.util.List<io.aura.contract.ApiRoute>) client.schema().get("routes")).stream()
                .filter(route -> route.path().equals("/generated/orders/search"))
                .findFirst().orElseThrow();
        assertThat(search.params())
                .extracting(io.aura.contract.ApiParameter::name, io.aura.contract.ApiParameter::source)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("direction", "query"),
                        org.assertj.core.groups.Tuple.tuple("minId", "query"));
        assertThat(search.params().get(0).schema())
                .containsEntry("type", "string")
                .containsEntry("enum", java.util.List.of("UP", "DOWN"));
        // A plain scalar publishes no schema on either path and spells its type the way
        // the reflective catalog does, so consumers infer the JSON type from one name.
        assertThat(search.params().get(1).schema()).isEmpty();
        assertThat(search.params().get(1).type()).isEqualTo("BigDecimal");
    }
}
