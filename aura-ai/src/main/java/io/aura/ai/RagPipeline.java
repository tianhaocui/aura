package io.aura.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic, bounded retrieval-to-context composition for RAG adapters. */
public final class RagPipeline {
    private final Retriever retriever;
    private final int maxResults;
    private final int maxContextCharacters;

    public RagPipeline(Retriever retriever, int maxResults, int maxContextCharacters) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        if (maxResults < 1) throw new IllegalArgumentException("maxResults must be positive");
        if (maxContextCharacters < 1) {
            throw new IllegalArgumentException("maxContextCharacters must be positive");
        }
        this.maxResults = maxResults;
        this.maxContextCharacters = maxContextCharacters;
    }

    public RagContext retrieve(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        List<String> candidates = retriever.retrieve(query, maxResults);
        if (candidates == null) throw new IllegalStateException("retriever returned null");
        List<String> passages = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || passages.size() >= maxResults) continue;
            String passage = candidate.trim();
            int separator = passages.isEmpty() ? 0 : 2;
            int remaining = maxContextCharacters - context.length() - separator;
            if (remaining <= 0) break;
            if (passage.length() > remaining) passage = passage.substring(0, remaining);
            if (separator > 0) context.append("\n\n");
            context.append(passage);
            passages.add(passage);
            if (passage.length() < candidate.trim().length()) break;
        }
        return new RagContext(query, passages, context.toString());
    }

    public record RagContext(String query, List<String> passages, String text) {
        public RagContext {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            passages = List.copyOf(Objects.requireNonNull(passages, "passages"));
            text = Objects.requireNonNull(text, "text");
        }
    }
}
