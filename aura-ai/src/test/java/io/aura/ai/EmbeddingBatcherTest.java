package io.aura.ai;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingBatcherTest {
    @Test
    void chunksRequestsAndValidatesAStableDimension() throws Exception {
        EmbeddingBatcher batcher = new EmbeddingBatcher((EmbeddingProvider) text -> List.of(1.0, (double) text.length()),
                2, 5, 2);

        EmbeddingBatch result = batcher.embed(List.of("one", "two", "three"));

        assertThat(result.texts()).containsExactly("one", "two", "three");
        assertThat(result.vectors()).hasSize(3).allSatisfy(vector -> assertThat(vector).hasSize(2));
        assertThat(result.dimension()).isEqualTo(2);
    }

    @Test
    void usesNativeBatchProviderAndRejectsInvalidResults() throws Exception {
        List<List<String>> seen = new ArrayList<>();
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override public List<Double> embed(String text) { return List.of(0.0, 0.0); }
            @Override public List<List<Double>> embedBatch(List<String> texts) {
                seen.add(texts);
                return texts.stream().map(text -> List.of(1.0, 2.0)).toList();
            }
        };
        EmbeddingBatcher batcher = new EmbeddingBatcher(provider, 2, 5, 2);
        batcher.embed(List.of("a", "b", "c"));
        assertThat(seen).containsExactly(List.of("a", "b"), List.of("c"));

        EmbeddingBatcher mismatch = new EmbeddingBatcher((EmbeddingProvider) text -> List.of(1.0, 2.0),
                2, 5, 3);
        assertThatThrownBy(() -> mismatch.embed(List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent dimensions");
    }

    @Test
    void rejectsUnboundedOrInvalidInputAndNonFiniteVectors() {
        EmbeddingBatcher batcher = new EmbeddingBatcher((EmbeddingProvider) text -> List.of(1.0), 1, 2, 1);
        assertThatThrownBy(() -> batcher.embed(List.of("a", "b", "c")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxItems");
        assertThatThrownBy(() -> batcher.embed(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);

        EmbeddingBatcher nonFinite = new EmbeddingBatcher((EmbeddingProvider) text -> List.of(Double.NaN), 1, 2, 1);
        assertThatThrownBy(() -> nonFinite.embed(List.of("a")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-finite");
    }

    @Test
    void allowsAggregateLimitBelowSingleBatchSizeAndRejectsInvalidEmptyBatchMetadata() throws Exception {
        EmbeddingBatcher batcher = new EmbeddingBatcher((EmbeddingProvider) text -> List.of(1.0), 8, 2, 1);
        assertThat(batcher.embed(List.of("a", "b")).vectors()).hasSize(2);
        assertThat(batcher.embed(List.of()).dimension()).isEqualTo(1);

        assertThatThrownBy(() -> new EmbeddingBatch(List.of(), List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new EmbeddingBatch(List.of(), null, 0))
                .isInstanceOf(NullPointerException.class);
    }
}
