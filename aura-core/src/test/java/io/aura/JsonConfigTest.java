package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JsonConfigTest {

    @Test
    void defaults() {
        JsonConfig config = new JsonConfig();
        assertThat(config.dateFormat()).isEqualTo("yyyy-MM-dd'T'HH:mm:ss.SSS");
        assertThat(config.writeNulls()).isFalse();
    }

    @Test
    void customDateFormat() {
        JsonConfig config = new JsonConfig();
        config.dateFormat("yyyy-MM-dd HH:mm:ss");
        assertThat(config.dateFormat()).isEqualTo("yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void writeNullsEnabled() {
        JsonConfig config = new JsonConfig();
        config.writeNulls(true);
        assertThat(config.writeNulls()).isTrue();
    }

    @Test
    void fluentApi() {
        JsonConfig config = new JsonConfig()
                .dateFormat("yyyy-MM-dd")
                .writeNulls(true);

        assertThat(config.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(config.writeNulls()).isTrue();
    }
}
