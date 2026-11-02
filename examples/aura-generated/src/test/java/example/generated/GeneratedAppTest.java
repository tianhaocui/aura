package example.generated;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

        JSONObject get = route(client, "/generated/orders/{id}");
        assertThat(get.getString("method")).isEqualTo("GET");
        assertThat(get.getString("description")).isEqualTo("Get one generated order");
        assertThat(parameter(get, "id").getString("description")).isEqualTo("Order identifier");

        JSONObject body = parameters(route(client, "/generated/orders", "POST")).getJSONObject(0);
        assertThat(body.getString("source")).isEqualTo("body");
        assertThat(body.getJSONObject("schema")).containsEntry("type", "object");
        assertThat(body.getJSONObject("schema").getJSONObject("properties")
                .getJSONObject("status")).containsEntry("minLength", 1);
        assertThat(parameters(route(client, "/generated/orders", "POST"))).hasSize(1);

        assertThat(get.getJSONObject("responseSchema")).containsEntry("type", "object");
        assertThat(get.getJSONObject("responseSchema").getJSONObject("properties").keySet())
                .contains("id", "status");
    }

    @Test
    void generatedRoutesBindEnumsAndDecimalsExactlyLikeTheReflectionPath() {
        TestClient client = TestClient.of(GeneratedApp.create());

        assertThat(client.get("/generated/orders/search")
                .query("direction", "UP").query("minId", "1")
                .expect(200).body()).contains("ready");

        JSONArray searchParams = parameters(route(client, "/generated/orders/search"));
        assertThat(searchParams).hasSize(2);
        assertThat(searchParams.getJSONObject(0)).containsEntry("name", "direction")
                .containsEntry("source", "query");
        assertThat(searchParams.getJSONObject(1)).containsEntry("name", "minId")
                .containsEntry("source", "query");
        assertThat(searchParams.getJSONObject(0).getJSONObject("schema"))
                .containsEntry("type", "string")
                .containsEntry("enum", java.util.List.of("UP", "DOWN"));
        // A plain scalar publishes no schema on either path and spells its type the way
        // the reflective catalog does, so consumers infer the JSON type from one name.
        JSONObject minId = searchParams.getJSONObject(1);
        assertThat(minId.getJSONObject("schema")).isNullOrEmpty();
        assertThat(minId.getString("type")).isEqualTo("BigDecimal");
    }

    // The document is read as parsed JSON, not as unchecked casts: a key that moved has to fail
    // the lookup that wanted it, instead of yielding a null that a later assertion can accept.

    private static JSONObject route(TestClient client, String path) {
        return route(client, path, "GET");
    }

    private static JSONObject route(TestClient client, String path, String method) {
        JSONArray routes = JSON.parseObject(JSON.toJSONString(client.schema())).getJSONArray("routes");
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            if (path.equals(route.getString("path")) && method.equals(route.getString("method"))) {
                return route;
            }
        }
        throw new AssertionError(method + " " + path + " never appeared in /__schema__: " + routes);
    }

    private static JSONArray parameters(JSONObject route) {
        JSONArray params = route.getJSONArray("params");
        return params == null ? new JSONArray() : params;
    }

    private static JSONObject parameter(JSONObject route, String name) {
        for (int i = 0; i < parameters(route).size(); i++) {
            JSONObject parameter = parameters(route).getJSONObject(i);
            if (name.equals(parameter.getString("name"))) return parameter;
        }
        throw new AssertionError(name + " never appeared in " + route);
    }
}
