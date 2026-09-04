package io.aura.ai;

import java.util.List;

/** Minimal provider-neutral retrieval extension point. */
@FunctionalInterface
public interface Retriever {
    List<String> retrieve(String query, int limit) throws Exception;
}
