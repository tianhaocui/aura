package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RequestTimeoutTest {

    @Test
    void requestTimeout_defaultZero() {
        Aura app = Aura.create();
        assertThat(app.requestTimeout()).isZero();
    }

    @Test
    void requestTimeout_setsValue() {
        Aura app = Aura.create().requestTimeout(30);
        assertThat(app.requestTimeout()).isEqualTo(30);
    }
}
