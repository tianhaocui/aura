package io.aura.ai;

import java.util.List;

/** Optional embedding provider used by RAG integrations. */
@FunctionalInterface
public interface EmbeddingProvider {
    List<Double> embed(String text) throws Exception;
}
