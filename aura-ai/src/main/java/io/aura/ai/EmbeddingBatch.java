package io.aura.ai;

import java.util.List;
import java.util.Objects;

/** Immutable, dimension-checked embedding result for one bounded request. */
public record EmbeddingBatch(List<String> texts, List<List<Double>> vectors, int dimension) {
    public EmbeddingBatch {
        texts = List.copyOf(Objects.requireNonNull(texts, "texts"));
        vectors = vectors == null ? List.of() : vectors.stream()
                .map(vector -> List.copyOf(Objects.requireNonNull(vector, "embedding vector")))
                .toList();
        if (texts.size() != vectors.size()) {
            throw new IllegalArgumentException("embedding text/vector counts must match");
        }
        if (dimension < 1 && !vectors.isEmpty()) {
            throw new IllegalArgumentException("embedding dimension must be positive");
        }
        if (!vectors.isEmpty()) {
            for (List<Double> vector : vectors) {
                if (vector.size() != dimension) {
                    throw new IllegalArgumentException("embedding vectors must have one dimension");
                }
                for (Double value : vector) {
                    if (value == null || !Double.isFinite(value)) {
                        throw new IllegalArgumentException("embedding values must be finite");
                    }
                }
            }
        }
    }
}
