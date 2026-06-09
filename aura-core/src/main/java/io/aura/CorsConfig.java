package io.aura;

import java.util.List;

public class CorsConfig {

    private List<String> origins = List.of("*");
    private String headers = "Content-Type, Authorization";
    private boolean credentials;

    public CorsConfig origins(String... origins) {
        this.origins = List.of(origins);
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
        if (requestOrigin == null) return null;
        for (String o : origins) {
            if ("*".equals(o) || o.equals(requestOrigin)) return requestOrigin;
        }
        return null;
    }
}
