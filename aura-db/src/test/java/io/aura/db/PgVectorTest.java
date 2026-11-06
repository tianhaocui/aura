package io.aura.db;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgVectorTest {

    @Test
    void formatsPgvectorsTextForm() {
        PgVector vector = PgVector.of(1f, -2.5f, 0f);

        assertThat(vector.literal()).isEqualTo("[1.0,-2.5,0.0]");
        assertThat(vector.dimensions()).isEqualTo(3);
        assertThat(vector.toString()).isEqualTo("[1.0,-2.5,0.0]");
    }

    @Test
    void acceptsDoublesAndCopiesTheInput() {
        float[] source = {1f, 2f};
        PgVector vector = PgVector.of(source);
        source[0] = 99f;

        assertThat(vector.toFloatArray()).containsExactly(1f, 2f);
        float[] read = vector.toFloatArray();
        read[1] = 99f;
        assertThat(vector.toFloatArray()).containsExactly(1f, 2f);

        assertThat(PgVector.of(1.5, 2.0).toFloatArray()).containsExactly(1.5f, 2f);
    }

    @Test
    void parsesTextListsArraysAndPgobjectStyleValues() {
        assertThat(PgVector.parse("[1, 2.5, -3e2]").toFloatArray())
                .containsExactly(1f, 2.5f, -300f);
        assertThat(PgVector.parse(List.of(1, 2.5)).toFloatArray())
                .containsExactly(1f, 2.5f);
        assertThat(PgVector.parse(new double[]{1.5, 2.5}).toFloatArray())
                .containsExactly(1.5f, 2.5f);
        assertThat(PgVector.parse(new Object() {
            @Override public String toString() { return "[4,5]"; }
        }).toFloatArray()).containsExactly(4f, 5f);

        PgVector existing = PgVector.of(7f);
        assertThat(PgVector.parse(existing)).isSameAs(existing);
    }

    @Test
    void rejectsEmptyNonFiniteAndMalformedVectors() {
        assertThatThrownBy(() -> PgVector.of(new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one dimension");
        assertThatThrownBy(() -> PgVector.of(new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgVector.of(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> PgVector.of(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> PgVector.of((float[]) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PgVector.parse(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> PgVector.parse("1,2,3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("look like");
        assertThatThrownBy(() -> PgVector.parse("[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one dimension");
        assertThatThrownBy(() -> PgVector.parse("[1,,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty element");
        assertThatThrownBy(() -> PgVector.parse("[1,nope]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
        assertThatThrownBy(() -> PgVector.parse(List.of(1, "two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void bindsAsAnUnspecifiedParameterSoPostgresInfersVector() throws Exception {
        BindingRecorder bound = BindingRecorder.bind(PgVector.of(1f, 2f));

        assertThat(bound.index).isEqualTo(2);
        assertThat(bound.value).isEqualTo("[1.0,2.0]");
        assertThat(bound.sqlType).isEqualTo(Types.OTHER);
    }

    @Test
    void equalityIsTheVectorContents() {
        assertThat(PgVector.of(1f, 2f)).isEqualTo(PgVector.of(1f, 2f));
        assertThat(PgVector.of(1f, 2f).hashCode()).isEqualTo(PgVector.of(1f, 2f).hashCode());
        assertThat(PgVector.of(1f, 2f)).isNotEqualTo(PgVector.of(1f, 3f));
        assertThat(PgVector.of(1f)).isNotEqualTo("[1.0]");
    }
}
