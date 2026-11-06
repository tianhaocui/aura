package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    void apiException_returnsStatusCodeAndField() {
        Router router = new Router();
        router.get("/orders", ctx -> {
            throw new ApiException(409, "ORDER_DUPLICATE", "order already exists", "orderId");
        });

        TestClient.Response resp = TestApplications.client(router).get("/orders").execute();

        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body()).contains("\"code\":\"ORDER_DUPLICATE\"");
        assertThat(resp.body()).contains("\"field\":\"orderId\"");
        assertThat(resp.body()).contains("order already exists");
        assertThat(resp.body()).contains("\"reason\":\"order already exists\"");
    }

    @Test
    void unexpectedProductionExceptionDoesNotExposeMessage() {
        Router router = new Router();
        router.get("/fail", ctx -> {
            throw new RuntimeException("jdbc:postgresql://db/internal?password=secret");
        });

        TestClient.Response resp = TestApplications.client(
                        new AuraConfig().env("prod").publicOrigin("https://localhost"), router)
                .get("/fail").execute();

        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).contains("Internal Server Error");
        assertThat(resp.body()).doesNotContain("jdbc:postgresql", "password", "secret");
    }

    @Test
    void codedProductionServerErrorDoesNotExposeMessage() {
        Router router = new Router();
        router.get("/fail", ctx -> {
            throw new ApiException(503, "UPSTREAM_FAILURE", "database password=secret");
        });

        TestClient.Response resp = TestApplications.client(
                        new AuraConfig().env("prod").publicOrigin("https://localhost"), router)
                .get("/fail").execute();

        assertThat(resp.status()).isEqualTo(503);
        assertThat(resp.body()).contains("Internal Server Error", "UPSTREAM_FAILURE");
        assertThat(resp.body()).doesNotContain("database", "secret");
    }

    @Test
    void illegalArgumentMessagesAreNotReflectedInProduction() {
        Router router = new Router();
        router.get("/bad", ctx -> {
            throw new IllegalArgumentException("table users has no column password_hash");
        });

        TestClient.Response resp = TestApplications.client(
                        new AuraConfig().env("prod").publicOrigin("https://localhost"), router)
                .get("/bad").execute();

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("VALIDATION_ERROR");
        assertThat(resp.body()).doesNotContain("users", "password_hash");
    }

    @Test
    void logSanitizerBoundsAndRemovesControlCharacters() {
        assertThat(UndertowStarter.safeLog("line1\r\nline2\tsecret", 12))
                .isEqualTo("line1  line2");
    }

    @Test
    void anOversizedBodyIsRefusedWith413RatherThanA500() {
        // One limit is read by the application, the other is enforced by the container while the
        // body streams in; both are the caller sending more than was agreed, which a 500 answers
        // by inviting the same request again.
        assertThat(ExceptionResolver.resolveDefault(TestApplications.runtime(),
                new RequestBodyTooLargeException("Request body exceeds maximum size")).status())
                .isEqualTo(413);
        assertThat(ExceptionResolver.resolveDefault(TestApplications.runtime(),
                new io.undertow.server.RequestTooBigException("UT000012: Request too big")).status())
                .isEqualTo(413);
    }
}
