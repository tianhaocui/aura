package io.aura;

import java.util.List;

public class CorsConfig {

    private List<String> origins = List.of("*");
    private String headers = "Content-Type, Authorization";
    private boolean credentials;

    public CorsConfig origins(String... origins) {
        if (origins == null || origins.length == 0) {
            this.origins = List.of();
        } else {
            this.origins = java.util.Arrays.stream(origins)
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return this;
    }

    public CorsConfig headers(String... headers) {
        this.headers = String.join(", ", headers);
        return this;
    }

    public CorsConfig credentials(boolean enabled) {
        this.credentials = enabled;
        return this;
    }

    public List<String> origins() { return origins; }
    public String headers() { return headers; }
    public boolean credentials() { return credentials; }

    public String resolveOrigin(String requestOrigin) {
        if (origins.contains("*") && !credentials) return "*";
        if (origins.contains("*") && credentials) return null;
        if (requestOrigin == null) return null;
        for (String o : origins) {
            if ("*".equals(o) || o.equals(requestOrigin)) return requestOrigin;
        }
        return null;
    }

    /** Wildcard origins must never be emitted with credentialed CORS responses. */
    public boolean hasWildcardOrigin() { return origins.contains("*"); }

    CorsConfig copy() {
        CorsConfig copy = new CorsConfig().origins(origins.toArray(String[]::new));
        copy.headers = headers;
        copy.credentials = credentials;
        return copy;
    }
}
