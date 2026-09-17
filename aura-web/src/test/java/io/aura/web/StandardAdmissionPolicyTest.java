package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardAdmissionPolicyTest {

    @Test
    void appliesIndependentIpAndApiKeyBucketsWithBoundedKeys() {
        StandardAdmissionPolicy byIp = StandardAdmissionPolicy.builder()
                .perIp(1, 2)
                .build();

        assertThat(byIp.admit(request("/orders", "198.51.100.1", "key-1")).allowed()).isTrue();
        assertThat(byIp.admit(request("/orders", "198.51.100.1", "key-2")).code())
                .isEqualTo(StandardAdmissionPolicy.RATE_LIMITED);
        assertThat(byIp.admit(request("/orders", "198.51.100.2", "key-1")).allowed()).isTrue();
        assertThat(byIp.admit(request("/orders", "198.51.100.3", "key-3")).allowed()).isFalse();
        assertThat(byIp.trackedRateKeys()).isEqualTo(2);
        assertThat(byIp.rateRejections()).isEqualTo(2);

        StandardAdmissionPolicy byKey = StandardAdmissionPolicy.builder()
                .perApiKey(1, 4)
                .build();
        assertThat(byKey.admit(request("/orders", "198.51.100.1", "key-1")).allowed()).isTrue();
        assertThat(byKey.admit(request("/orders", "198.51.100.2", "key-1")).allowed()).isFalse();
        assertThat(byKey.admit(request("/orders", "198.51.100.2", "key-2")).allowed()).isTrue();
    }

    @Test
    void routeRulesUseTheRouteTemplateAndReleaseConcurrencyLeaseOnce() {
        StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
                .routeRate("/orders/{id}", 1)
                .routeConcurrency("/orders/{id}", 1)
                .build();

        AdmissionDecision first = policy.admit(request("/orders/{id}", "198.51.100.1", "key-1"));
        assertThat(first.allowed()).isTrue();
        assertThat(policy.inFlight()).isEqualTo(1);
        assertThat(policy.admit(request("/orders/{id}", "198.51.100.2", "key-2")).code())
                .isEqualTo(StandardAdmissionPolicy.RATE_LIMITED);
        assertThat(policy.admit(request("/health", "198.51.100.1", "key-1")).allowed()).isTrue();

        first.close();
        first.close();
        assertThat(policy.inFlight()).isZero();
    }

    @Test
    void bulkheadRejectsAndRecoversAfterLeaseRelease() {
        StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
                .globalConcurrency(1)
                .build();
        AdmissionDecision first = policy.admit(request("/orders", "198.51.100.1", "key"));
        AdmissionDecision rejected = policy.admit(request("/orders", "198.51.100.2", "key"));

        assertThat(first.allowed()).isTrue();
        assertThat(rejected.status()).isEqualTo(503);
        assertThat(rejected.code()).isEqualTo(StandardAdmissionPolicy.CONCURRENCY_LIMITED);
        assertThat(policy.concurrencyRejections()).isEqualTo(1);

        first.close();
        AdmissionDecision next = policy.admit(request("/orders", "198.51.100.2", "key"));
        assertThat(next.allowed()).isTrue();
        next.close();
    }

    @Test
    void validatesRuleConfiguration() {
        assertThatThrownBy(() -> StandardAdmissionPolicy.builder().build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StandardAdmissionPolicy.builder().perIp(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StandardAdmissionPolicy.builder().routeRate("orders", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AdmissionRequest request(String route, String ip, String apiKey) {
        return new AdmissionRequest("GET", route.replace("{id}", "42"), route, ip, apiKey);
    }
}
