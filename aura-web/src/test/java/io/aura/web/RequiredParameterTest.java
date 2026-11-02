package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A route that declares a parameter required is a route that answers 400 without it, and a
 * parameter the handler can receive as null is a parameter the schema does not mark required.
 *
 * <p>The pair is asserted together on purpose: while they were two rules, a model reading
 * {@code /__schema__} could withhold a parameter the contract called mandatory and watch the
 * handler run on a zero, or send an optional one the server refused to explain.
 */
class RequiredParameterTest {

    static class RankService {
        public String rank(int limit, String tag) {
            return "limit=" + limit + " tag=" + tag;
        }
    }

    static class FeedService {
        public String feed(List<String> tags) {
            return "tags=" + tags;
        }
    }

    private static TestClient client() {
        Router router = new Router();
        router.get("/rank", new RankService(), "rank");
        router.post("/feed", new FeedService(), "feed");
        return TestApplications.client(router);
    }

    @Test
    void missingPrimitiveParameterIsRefusedAndNamedInsteadOfBindingZero() {
        TestClient.Response resp = client().get("/rank").execute();

        resp.expect(400);
        assertThat(resp.body())
                .contains("AURA_PARAMETER_REQUIRED")
                .contains("\"field\":\"limit\"");
    }

    @Test
    void presentPrimitiveParameterAndNullableReferenceStillBind() {
        client().get("/rank").query("limit", "3").execute()
                .expect(200).bodyContains("limit=3 tag=null");
    }

    @Test
    void schemaMarksExactlyTheParameterTheBinderRefuses() {
        TestClient client = client();
        JSONObject rank = routeDocument(client, "/rank");

        assertThat(parameter(rank, "limit").getBoolean("required"))
                .as("a primitive signature cannot hold null, so absence is a client error")
                .isTrue();
        assertThat(parameter(rank, "limit").getString("source")).isEqualTo("query");
        assertThat(parameter(rank, "tag").getBoolean("required"))
                .as("null is a value this handler chose to accept")
                .isFalse();

        client.get("/rank").execute().expect(400);
        client.get("/rank").query("limit", "1").execute().expect(200);
    }

    @Test
    void absentBodyIsNotMarkedRequiredBecauseTheHandlerCanSeeNull() {
        TestClient client = client();
        JSONObject feed = routeDocument(client, "/feed");

        assertThat(parameter(feed, "tags").getString("source")).isEqualTo("body");
        assertThat(parameter(feed, "tags").getBoolean("required")).isFalse();

        client.post("/feed").execute().expect(200).bodyContains("tags=null");
        client.post("/feed").body(List.of("a", "b")).execute()
                .expect(200).bodyContains("tags=[a, b]");
    }

    /** The route's entry in the wire document, so the assertions read the keys a client reads. */
    private static JSONObject routeDocument(TestClient client, String path) {
        JSONObject document = JSON.parseObject(client.get("/__schema__").execute().body());
        JSONArray routes = document.getJSONArray("routes");
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            if (path.equals(route.getString("path"))) return route;
        }
        throw new AssertionError(path + " never appeared in /__schema__: " + document);
    }

    private static JSONObject parameter(JSONObject route, String name) {
        JSONArray parameters = route.getJSONArray("params");
        for (int i = 0; i < parameters.size(); i++) {
            JSONObject parameter = parameters.getJSONObject(i);
            if (name.equals(parameter.getString("name"))) return parameter;
        }
        throw new AssertionError(name + " never appeared in " + route);
    }
}
