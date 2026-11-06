package io.aura.ai;

import java.util.List;
import java.util.Objects;

/** Optional embedding provider used by RAG integrations. */
@FunctionalInterface
public interface EmbeddingProvider {
    List<Double> embed(String text) throws Exception;

    /**
     * Provider-neutral batch fallback. Providers may override this to use a native batch API;
     * callers should apply an explicit bound before invoking it.
     */
    default List<List<Double>> embedBatch(List<String> texts) throws Exception {
        Objects.requireNonNull(texts, "texts");
        java.util.ArrayList<List<Double>> vectors = new java.util.ArrayList<>(texts.size());
        for (String text : texts) vectors.add(embed(text));
        return List.copyOf(vectors);
    }
}
