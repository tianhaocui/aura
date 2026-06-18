package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CorsConfigTest {

    @Test
    void defaults_allowAll() {
        CorsConfig config = new CorsConfig();
        assertThat(config.origins()).containsExactly("*");
        assertThat(config.headers()).isEqualTo("Content-Type, Authorization");
        assertThat(config.credentials()).isFalse();
    }

    @Test
    void origins_setsSpecificOrigins() {
        CorsConfig config = new CorsConfig().origins("https://example.com", "https://app.example.com");
        assertThat(config.origins()).containsExactly("https://example.com", "https://app.example.com");
    }

    @Test
    void headers_setsCustomHeaders() {
        CorsConfig config = new CorsConfig().headers("Content-Type", "X-Custom");
        assertThat(config.headers()).isEqualTo("Content-Type, X-Custom");
    }

    @Test
    void credentials_enabledDisablesWildcardOrigin() {
        CorsConfig config = new CorsConfig().credentials(true);
        assertThat(config.credentials()).isTrue();
        // With credentials=true, resolveOrigin should not return "*"
        assertThat(config.resolveOrigin("https://example.com")).isEqualTo("https://example.com");
    }

    @Test
    void resolveOrigin_wildcardNoCredentials_returnsStar() {
        CorsConfig config = new CorsConfig();
        assertThat(config.resolveOrigin("https://anything.com")).isEqualTo("*");
    }

    @Test
    void resolveOrigin_wildcardWithCredentials_returnsRequestOrigin() {
        CorsConfig config = new CorsConfig().credentials(true);
        assertThat(config.resolveOrigin("https://myapp.com")).isEqualTo("https://myapp.com");
    }

    @Test
    void resolveOrigin_specificOriginMatch_returnsOrigin() {
        CorsConfig config = new CorsConfig().origins("https://allowed.com");
        assertThat(config.resolveOrigin("https://allowed.com")).isEqualTo("https://allowed.com");
    }

    @Test
    void resolveOrigin_specificOriginNoMatch_returnsNull() {
        CorsConfig config = new CorsConfig().origins("https://allowed.com");
        assertThat(config.resolveOrigin("https://evil.com")).isNull();
    }

    @Test
    void resolveOrigin_nullRequestOrigin_returnsNull() {
        CorsConfig config = new CorsConfig().origins("https://allowed.com");
        assertThat(config.resolveOrigin(null)).isNull();
    }

    @Test
    void resolveOrigin_wildcardNullRequestOrigin_returnsStar() {
        CorsConfig config = new CorsConfig();
        // wildcard + no credentials → always returns "*"
        assertThat(config.resolveOrigin(null)).isEqualTo("*");
    }

    @Test
    void fluent_api() {
        CorsConfig config = new CorsConfig()
                .origins("https://a.com")
                .headers("Authorization")
                .credentials(true);
        assertThat(config.origins()).containsExactly("https://a.com");
        assertThat(config.headers()).isEqualTo("Authorization");
        assertThat(config.credentials()).isTrue();
    }
}
