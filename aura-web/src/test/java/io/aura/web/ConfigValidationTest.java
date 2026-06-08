package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ConfigValidationTest {

    // requestTimeout builder method clamps negative to 0
    @Test
    void requestTimeout_negativeClampedToZero_viaBuilder() {
        Aura app = Aura.create().requestTimeout(-1);
        assertThat(app.requestTimeout()).isEqualTo(0);
    }

    @Test
    void requestTimeout_negativeHundredClampedToZero_viaBuilder() {
        Aura app = Aura.create().requestTimeout(-999);
        assertThat(app.requestTimeout()).isEqualTo(0);
    }

    // gzipMinSize builder clamps negative to 0
    @Test
    void gzipMinSize_negativeClampedToZero_viaBuilder() {
        Aura app = Aura.create().gzipMinSize(-1);
        assertThat(app.gzipMinSize()).isEqualTo(0);
    }

    // Verify that reasonable positive values work as expected
    @Test
    void requestTimeout_positiveValue_preserved() {
        Aura app = Aura.create().requestTimeout(60);
        assertThat(app.requestTimeout()).isEqualTo(60);
    }

    @Test
    void gzipMinSize_positiveValue_preserved() {
        Aura app = Aura.create().gzipMinSize(2048);
        assertThat(app.gzipMinSize()).isEqualTo(2048);
    }

    // Zero means disabled for timeout, means "compress everything" for gzip
    @Test
    void requestTimeout_zeroMeansDisabled() {
        Aura app = Aura.create().requestTimeout(0);
        assertThat(app.requestTimeout()).isEqualTo(0);
    }

    @Test
    void gzipMinSize_zeroMeansCompressAll() {
        Aura app = Aura.create().gzipMinSize(0);
        assertThat(app.gzipMinSize()).isEqualTo(0);
    }
}
