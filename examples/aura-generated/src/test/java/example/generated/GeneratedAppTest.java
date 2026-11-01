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
        var routes = (java.util.List<java.util.Map<String, Object>>) client.schema().get("routes");
        var create = routes.stream().filter(route -> route.get("path").equals("/generated/orders")
                && route.get("method").equals("POST")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var createParams = (java.util.List<io.aura.contract.ApiParameter>) create.get("params");
        assertThat(createParams).singleElement()
                .satisfies(parameter -> {
                    assertThat(parameter.schema()).containsEntry("type", "object");
                    @SuppressWarnings("unchecked")
                    var properties = (java.util.Map<String, Object>) parameter.schema().get("properties");
                    @SuppressWarnings("unchecked")
                    var status = (java.util.Map<String, Object>) properties.get("status");
                    assertThat(status).containsEntry("minLength", 1);
                });
        var get = routes.stream().filter(route -> route.get("path").equals("/generated/orders/{id}")
                && route.get("method").equals("GET")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var responseSchema = (java.util.Map<String, Object>) get.get("responseSchema");
        assertThat(responseSchema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        var responseProperties = (java.util.Map<String, Object>) responseSchema.get("properties");
        assertThat(responseProperties.keySet()).contains("id", "status");
    }

    @Test
    void generatedRoutesBindEnumsAndDecimalsExactlyLikeTheReflectionPath() {
        TestClient client = TestClient.of(GeneratedApp.create());

        assertThat(client.get("/generated/orders/search")
                .query("direction", "UP").query("minId", "1")
                .expect(200).body()).contains("ready");

        @SuppressWarnings("unchecked")
        var searchRoutes = (java.util.List<java.util.Map<String, Object>>) client.schema().get("routes");
        var search = searchRoutes.stream()
                .filter(route -> route.get("path").equals("/generated/orders/search"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var searchParams = (java.util.List<io.aura.contract.ApiParameter>) search.get("params");
        assertThat(searchParams)
                .extracting(io.aura.contract.ApiParameter::name, io.aura.contract.ApiParameter::source)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("direction", "query"),
                        org.assertj.core.groups.Tuple.tuple("minId", "query"));
        assertThat(searchParams.get(0).schema())
                .containsEntry("type", "string")
                .containsEntry("enum", java.util.List.of("UP", "DOWN"));
        // A plain scalar publishes no schema on either path and spells its type the way
        // the reflective catalog does, so consumers infer the JSON type from one name.
        assertThat(searchParams.get(1).schema()).isEmpty();
        assertThat(searchParams.get(1).type()).isEqualTo("BigDecimal");
    }
}
