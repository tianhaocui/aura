package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenericBodyBindingTest {
    @Test
    void lambdaRoutesDecodeParameterizedCollections() {
        TestClient client = client();

        assertThat(client.post("/batch")
                .body(List.of(new Item("a"), new Item("b")))
                .expect(200).body()).contains("a,b");
        assertThat(client.post("/lookup")
                .body(Map.of("a", 1, "b", 2))
                .expect(200).body()).contains("2");
    }

    @Test
    void missingCollectionBodyPreservesNullableBodyBinding() {
        TestClient client = client();
        assertThat(client.post("/batch").expect(200).body()).contains("null");
    }

    private static TestClient client() {
        Router router = new Router();
        router.post("/batch", new LambdaHandler(new BatchService()));
        router.post("/lookup", new LambdaHandler(new LookupService()));
        return TestApplications.client(router);
    }

    @FunctionalInterface
    interface Batch { String batch(List<Item> items); }

    static final class BatchService implements Batch {
        public String batch(List<Item> items) {
            if (items == null) return "null";
            return items.stream().map(Item::name).reduce((left, right) -> left + "," + right).orElse("");
        }
    }

    @FunctionalInterface
    interface Lookup { String lookup(Map<String, Integer> values); }

    static final class LookupService implements Lookup {
        public String lookup(Map<String, Integer> values) {
            return String.valueOf(values.get("b"));
        }
    }

    record Item(String name) { }
}
