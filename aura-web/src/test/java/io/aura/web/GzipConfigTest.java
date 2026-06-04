package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GzipConfigTest {

    @Test
    void gzip_defaultDisabled() {
        Aura app = Aura.create();
        assertThat(app.gzip()).isFalse();
    }

    @Test
    void gzip_enabled() {
        Aura app = Aura.create().gzip(true);
        assertThat(app.gzip()).isTrue();
    }

    @Test
    void gzipMinSize_default1024() {
        Aura app = Aura.create();
        assertThat(app.gzipMinSize()).isEqualTo(1024);
    }

    @Test
    void gzipMinSize_custom() {
        Aura app = Aura.create().gzipMinSize(512);
        assertThat(app.gzipMinSize()).isEqualTo(512);
    }
}
