package io.aura.web;

import io.aura.Aura;
import io.aura.annotation.Post;
import io.aura.annotation.Path;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenericBodyBindingTest {
    @Test
    void reflectionRoutesDecodeParameterizedCollections() {
        TestClient client = TestClient.of(Aura.create().service(new CollectionService()));

        assertThat(client.post("/batch")
                .body(List.of(new Item("a"), new Item("b")))
                .expect(200).body()).contains("a,b");
        assertThat(client.post("/lookup")
                .body(Map.of("a", 1, "b", 2))
                .expect(200).body()).contains("2");
    }

    @Test
    void missingCollectionBodyPreservesNullableBodyBinding() {
        TestClient client = TestClient.of(Aura.create().service(new CollectionService()));
        assertThat(client.post("/batch").expect(200).body()).contains("null");
    }

    @Path("")
    static final class CollectionService {
        @Post("/batch")
        public String batch(List<Item> items) {
            if (items == null) return "null";
            return items.stream().map(Item::name).reduce((left, right) -> left + "," + right).orElse("");
        }

        @Post("/lookup")
        public String lookup(Map<String, Integer> values) {
            return String.valueOf(values.get("b"));
        }
    }

    record Item(String name) { }
}
