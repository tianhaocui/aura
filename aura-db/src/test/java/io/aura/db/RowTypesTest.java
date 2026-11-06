package io.aura.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowTypesTest {

    @Test
    void readsJsonAsRawTextOrAsAType() {
        Row row = Row.of("document")
                .set("payload", Jsonb.of(Map.of("kind", "note", "stars", 5)))
                .set("driver_text", "{\"kind\":\"legacy\"}")
                .set("missing", null);

        assertThat(row.getJson("payload")).contains("\"kind\"").contains("\"note\"");
        assertThat(row.getJson("payload", Map.class))
                .containsEntry("kind", "note")
                .containsEntry("stars", 5);
        assertThat(row.getJson("payload", String.class)).isEqualTo(row.getJson("payload"));
        assertThat(row.getJson("driver_text", Map.class)).containsEntry("kind", "legacy");
        assertThat(row.getJson("missing")).isNull();
        assertThat(row.getJson("missing", Map.class)).isNull();
    }

    @Test
    void readsVectorsFromEveryShapeTheDriverCanReturn() {
        Row row = Row.of("item")
                .set("stored", PgVector.of(1f, 2f))
                .set("array", new float[]{3f, 4f})
                .set("driver_text", "[5, 6]")
                .set("missing", null);

        assertThat(row.getVector("stored")).containsExactly(1f, 2f);
        assertThat(row.getVector("array")).containsExactly(3f, 4f);
        assertThat(row.getVector("driver_text")).containsExactly(5f, 6f);
        assertThat(row.getVector("missing")).isNull();
    }

    @Test
    void setJsonAndSetVectorStoreBindableValues() {
        Row row = Row.of("document")
                .setJson("payload", Map.of("kind", "note"))
                .setVector("embedding", 1f, 2f);

        assertThat(row.get("payload")).isInstanceOf(Jsonb.class);
        assertThat(row.get("embedding")).isInstanceOf(PgVector.class);
        assertThat(row.getVector("embedding")).containsExactly(1f, 2f);
    }

    @Test
    void typedJsonParsingSurfacesBrokenText() {
        Row row = Row.of("document").set("payload", "{not json");

        assertThatThrownBy(() -> row.getJson("payload", Map.class))
                .isInstanceOf(RuntimeException.class);
    }
}
