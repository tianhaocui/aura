package io.aura.web;

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

    @FunctionalInterface
    interface Rank { String rank(int limit, String tag); }

    static class RankService implements Rank {
        public String rank(int limit, String tag) {
            return "limit=" + limit + " tag=" + tag;
        }
    }

    @FunctionalInterface
    interface Feed { String feed(List<String> tags); }

    static class FeedService implements Feed {
        public String feed(List<String> tags) {
            return "tags=" + tags;
        }
    }

    private static TestClient client() {
        Router router = new Router();
        router.get("/rank", new LambdaHandler(new RankService()));
        router.post("/feed", new LambdaHandler(new FeedService()));
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
        JSONObject rank = SchemaWire.route(client, "/rank");

        assertThat(SchemaWire.parameter(rank, "limit").getBoolean("required"))
                .as("a primitive signature cannot hold null, so absence is a client error")
                .isTrue();
        assertThat(SchemaWire.parameter(rank, "limit").getString("source")).isEqualTo("query");
        assertThat(SchemaWire.parameter(rank, "tag").getBoolean("required"))
                .as("null is a value this handler chose to accept")
                .isFalse();

        client.get("/rank").execute().expect(400);
        client.get("/rank").query("limit", "1").execute().expect(200);
    }

    @Test
    void absentBodyIsNotMarkedRequiredBecauseTheHandlerCanSeeNull() {
        TestClient client = client();
        JSONObject feed = SchemaWire.route(client, "/feed");

        assertThat(SchemaWire.parameter(feed, "tags").getString("source")).isEqualTo("body");
        assertThat(SchemaWire.parameter(feed, "tags").getBoolean("required")).isFalse();

        client.post("/feed").execute().expect(200).bodyContains("tags=null");
        client.post("/feed").body(List.of("a", "b")).execute()
                .expect(200).bodyContains("tags=[a, b]");
    }
}
