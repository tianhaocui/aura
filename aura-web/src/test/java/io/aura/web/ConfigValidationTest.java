package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ConfigValidationTest {

    @Test
    void requestTimeout_negativeRejectedByValidation() {
        AuraConfig config = new AuraConfig().requestTimeout(-1);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout");
    }

    @Test
    void requestTimeout_negativeHundredRejectedByValidation() {
        AuraConfig config = new AuraConfig().requestTimeout(-999);
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout");
    }

    @Test
    void gzipMinSize_negativeClampedToZero() {
        AuraConfig config = new AuraConfig().gzipMinSize(-1);
        assertThat(config.gzipMinSize()).isEqualTo(0);
    }

    // Verify that reasonable positive values work as expected
    @Test
    void requestTimeout_positiveValue_preserved() {
        AuraConfig config = new AuraConfig().requestTimeout(60);
        assertThat(config.validate().requestTimeout()).isEqualTo(60);
    }

    @Test
    void gzipMinSize_positiveValue_preserved() {
        AuraConfig config = new AuraConfig().gzipMinSize(2048);
        assertThat(config.gzipMinSize()).isEqualTo(2048);
    }

    // Zero means disabled for timeout, means "compress everything" for gzip
    @Test
    void requestTimeout_zeroMeansDisabled() {
        AuraConfig config = new AuraConfig().requestTimeout(0);
        assertThat(config.validate().requestTimeout()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_zeroMeansCompressAll() {
        AuraConfig config = new AuraConfig().gzipMinSize(0);
        assertThat(config.gzipMinSize()).isEqualTo(0);
    }
}
