package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code example} in {@code /__schema__} is the one thing a model copies verbatim, so it has
 * to be a value the same route's binder accepts. It used to be built from a private list of Java
 * types that knew nothing about {@code BigDecimal} or {@code enum}, which published a request the
 * server then refused.
 */
class SchemaExampleTest {

    static class ReportService {
        public String report(BigDecimal rate) {
            return "rate=" + rate;
        }
    }

    @Test
    void publishedExampleIsAValueTheBinderAccepts() {
        Router router = new Router();
        router.get("/report", new ReportService(), "report");
        TestClient client = TestApplications.client(router);

        JSONObject route = routeDocument(client, "/report");
        assertThat(route.getString("example"))
                .as("the type table that classes rate as number also writes its example")
                .contains("rate=1.0")
                .doesNotContain("rate=example");

        // Replay what the document told the client to send.
        client.get("/report").query("rate", "1.0").execute()
                .expect(200).bodyContains("rate=1.0");
    }

    private static JSONObject routeDocument(TestClient client, String path) {
        JSONObject document = JSON.parseObject(client.get("/__schema__").execute().body());
        JSONArray routes = document.getJSONArray("routes");
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            if (path.equals(route.getString("path"))) return route;
        }
        throw new AssertionError(path + " never appeared in /__schema__: " + document);
    }
}
