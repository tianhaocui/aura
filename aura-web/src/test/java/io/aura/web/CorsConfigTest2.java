package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CorsConfigTest2 {

    @Test
    void corsConfig_multipleOrigins_matchesRequest() {
        Aura app = Aura.create().cors(c -> c
                .origins("https://app.com", "https://admin.app.com")
                .headers("Content-Type", "Authorization", "X-Request-Id")
                .credentials(true));

        assertThat(app.corsConfig()).isNotNull();
        assertThat(app.corsConfig().origins()).containsExactly("https://app.com", "https://admin.app.com");
        assertThat(app.corsConfig().headers()).isEqualTo("Content-Type, Authorization, X-Request-Id");
        assertThat(app.corsConfig().credentials()).isTrue();
    }

    @Test
    void corsConfig_resolveOrigin_matchesKnown() {
        Aura app = Aura.create().cors(c -> c.origins("https://a.com", "https://b.com"));
        assertThat(app.corsConfig().resolveOrigin("https://a.com")).isEqualTo("https://a.com");
        assertThat(app.corsConfig().resolveOrigin("https://b.com")).isEqualTo("https://b.com");
        assertThat(app.corsConfig().resolveOrigin("https://evil.com")).isNull();
    }

    @Test
    void corsConfig_wildcard_withoutCredentials() {
        Aura app = Aura.create().cors(c -> c.origins("*"));
        assertThat(app.corsConfig().resolveOrigin("https://any.com")).isEqualTo("*");
    }

    @Test
    void corsConfig_wildcard_withCredentials_returnsActualOrigin() {
        Aura app = Aura.create().cors(c -> c.origins("*").credentials(true));
        assertThat(app.corsConfig().resolveOrigin("https://any.com")).isEqualTo("https://any.com");
    }

    @Test
    void corsTrue_stillWorks() {
        Aura app = Aura.create().cors(true);
        assertThat(app.corsOrigin()).isEqualTo("*");
        assertThat(app.corsConfig()).isNull();
    }
}
