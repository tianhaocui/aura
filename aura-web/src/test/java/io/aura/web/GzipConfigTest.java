package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GzipConfigTest {

    @Test
    void gzip_defaultDisabled() {
        AuraConfig config = new AuraConfig();
        assertThat(config.gzip()).isFalse();
    }

    @Test
    void gzip_enabled() {
        AuraConfig config = new AuraConfig().gzip(true);
        assertThat(config.gzip()).isTrue();
    }

    @Test
    void gzipMinSize_default1024() {
        AuraConfig config = new AuraConfig();
        assertThat(config.gzipMinSize()).isEqualTo(1024);
    }

    @Test
    void gzipMinSize_custom() {
        AuraConfig config = new AuraConfig().gzipMinSize(512);
        assertThat(config.gzipMinSize()).isEqualTo(512);
    }
}
