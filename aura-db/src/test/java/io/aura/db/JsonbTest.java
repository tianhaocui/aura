package io.aura.db;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonbTest {

    @Test
    void serialisesMapsAndLists() {
        assertThat(JSON.parseObject(Jsonb.of(Map.of("kind", "note", "stars", 5)).json()))
                .containsEntry("kind", "note")
                .containsEntry("stars", 5);
        assertThat(Jsonb.of(java.util.List.of(1, 2, 3)).json()).isEqualTo("[1,2,3]");
    }

    @Test
    void aStringBecomesAJsonStringScalarUnlessRawIsUsed() {
        assertThat(Jsonb.of((Object) "aura").json()).isEqualTo("\"aura\"");
        assertThat(Jsonb.raw("{\"kind\":\"note\"}").json()).isEqualTo("{\"kind\":\"note\"}");
    }

    @Test
    void anExistingWrapperIsReturnedAndNullsAreRejected() {
        Jsonb existing = Jsonb.raw("{}");

        assertThat(Jsonb.of(existing)).isSameAs(existing);
        assertThatThrownBy(() -> Jsonb.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Jsonb.raw(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindsAsAnUnspecifiedParameterSoPostgresInfersJsonb() throws Exception {
        BindingRecorder bound = BindingRecorder.bind(Jsonb.raw("{\"a\":1}"));

        assertThat(bound.index).isEqualTo(2);
        assertThat(bound.value).isEqualTo("{\"a\":1}");
        assertThat(bound.sqlType).isEqualTo(Types.OTHER);
    }

    @Test
    void equalityIsTheJsonText() {
        assertThat(Jsonb.raw("{\"a\":1}")).isEqualTo(Jsonb.raw("{\"a\":1}"));
        assertThat(Jsonb.raw("{\"a\":1}").hashCode())
                .isEqualTo(Jsonb.raw("{\"a\":1}").hashCode());
        assertThat(Jsonb.raw("{\"a\":1}")).isNotEqualTo(Jsonb.raw("{\"a\":2}"));
        assertThat(Jsonb.raw("{}")).isNotEqualTo("{}");
    }
}
