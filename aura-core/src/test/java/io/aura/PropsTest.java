package io.aura;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropsTest {

    @Test
    void props_returnsByPrefix() {
        Aura app = Aura.create();
        app.set("bnp.api.key", "abc123");
        app.set("bnp.api.url", "https://example.com");
        app.set("other.key", "val");

        Map<String, String> result = app.props("bnp.");
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("bnp.api.key", "abc123");
        assertThat(result).containsEntry("bnp.api.url", "https://example.com");
        assertThat(result).doesNotContainKey("other.key");
    }

    @Test
    void props_emptyWhenNoneMatch() {
        Aura app = Aura.create();
        app.set("foo.bar", "baz");

        Map<String, String> result = app.props("xyz.");
        assertThat(result).isEmpty();
    }

    @Test
    void props_includesConfigFileEntries() {
        Aura app = Aura.create();
        // aura.properties in test resources has app.name=base, aura.port=8080
        Map<String, String> result = app.props("app.");
        assertThat(result).containsKey("app.name");
    }
}
