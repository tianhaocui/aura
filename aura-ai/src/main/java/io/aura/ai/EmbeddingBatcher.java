package io.aura.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded adapter that makes batch size and vector dimension explicit for AI-generated code. */
public final class EmbeddingBatcher {
    private final EmbeddingProvider provider;
    private final int maxBatchSize;
    private final int maxItems;
    private final int expectedDimension;

    public EmbeddingBatcher(EmbeddingProvider provider, int maxBatchSize, int maxItems,
                            int expectedDimension) {
        this.provider = Objects.requireNonNull(provider, "provider");
        if (maxBatchSize < 1) throw new IllegalArgumentException("maxBatchSize must be positive");
        if (expectedDimension < 0) {
            throw new IllegalArgumentException("expectedDimension must not be negative");
        }
        this.maxBatchSize = maxBatchSize;
        this.maxItems = maxItems;
        this.expectedDimension = expectedDimension;
    }

    public int maxBatchSize() { return maxBatchSize; }
    public int maxItems() { return maxItems; }
    public int expectedDimension() { return expectedDimension; }

    public EmbeddingBatch embed(List<String> texts) throws Exception {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) return new EmbeddingBatch(List.of(), List.of(), expectedDimension);
        if (texts.size() > maxItems) {
            throw new IllegalArgumentException("embedding request exceeds maxItems");
        }
        List<String> input = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("embedding text must not be blank");
            input.add(text);
        }
        List<List<Double>> vectors = new ArrayList<>(texts.size());
        int dimension = expectedDimension;
        for (int start = 0; start < input.size(); start += maxBatchSize) {
            int end = Math.min(input.size(), start + maxBatchSize);
            List<List<Double>> batch = provider.embedBatch(List.copyOf(input.subList(start, end)));
            if (batch == null || batch.size() != end - start) {
                throw new IllegalStateException("embedding provider returned an invalid batch size");
            }
            for (List<Double> vector : batch) {
                if (vector == null || vector.isEmpty()) {
                    throw new IllegalStateException("embedding provider returned an empty vector");
                }
                if (dimension == 0) dimension = vector.size();
                if (vector.size() != dimension) {
                    throw new IllegalStateException("embedding provider returned inconsistent dimensions");
                }
                for (Double value : vector) {
                    if (value == null || !Double.isFinite(value)) {
                        throw new IllegalStateException("embedding provider returned a non-finite value");
                    }
                }
                vectors.add(List.copyOf(vector));
            }
        }
        return new EmbeddingBatch(input, vectors, dimension);
    }
}
