package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RequestTimeoutTest {

    @Test
    void requestTimeout_defaultZero() {
        assertThat(TestApplications.runtime().requestTimeout()).isZero();
    }

    @Test
    void requestTimeout_setsValue() {
        assertThat(TestApplications.runtime(new AuraConfig().requestTimeout(30)).requestTimeout())
                .isEqualTo(30);
    }
}
