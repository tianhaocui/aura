package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    void apiException_returnsStatusCodeAndField() {
        Aura app = Aura.create();
        Router router = new Router();
        router.get("/orders", ctx -> {
            throw new ApiException(409, "ORDER_DUPLICATE", "order already exists", "orderId");
        });

        TestClient.Response resp = new TestClient(app, router).get("/orders").execute();

        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body()).contains("\"code\":\"ORDER_DUPLICATE\"");
        assertThat(resp.body()).contains("\"field\":\"orderId\"");
        assertThat(resp.body()).contains("order already exists");
    }

    @Test
    void unexpectedProductionExceptionDoesNotExposeMessage() {
        Aura app = Aura.create().env("prod");
        Router router = new Router();
        router.get("/fail", ctx -> {
            throw new RuntimeException("jdbc:postgresql://db/internal?password=secret");
        });

        TestClient.Response resp = new TestClient(app, router).get("/fail").execute();

        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).contains("Internal Server Error");
        assertThat(resp.body()).doesNotContain("jdbc:postgresql", "password", "secret");
    }

    @Test
    void codedProductionServerErrorDoesNotExposeMessage() {
        Aura app = Aura.create().env("prod");
        Router router = new Router();
        router.get("/fail", ctx -> {
            throw new ApiException(503, "UPSTREAM_FAILURE", "database password=secret");
        });

        TestClient.Response resp = new TestClient(app, router).get("/fail").execute();

        assertThat(resp.status()).isEqualTo(503);
        assertThat(resp.body()).contains("Internal Server Error", "UPSTREAM_FAILURE");
        assertThat(resp.body()).doesNotContain("database", "secret");
    }

    @Test
    void logSanitizerBoundsAndRemovesControlCharacters() {
        assertThat(UndertowStarter.safeLog("line1\r\nline2\tsecret", 12))
                .isEqualTo("line1  line2");
    }
}
