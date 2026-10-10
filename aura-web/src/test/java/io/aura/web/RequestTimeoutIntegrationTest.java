package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTimeoutIntegrationTest {

    @Test
    void slowHandlerTimesOutWithRealHttp() throws Exception {
        int port = freePort();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean handlerCompleted = new AtomicBoolean(false);

        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).requestTimeout(1),
                "request-timeout-integration",
                new RouteContribution("GET", "/slow", (BaseHandler) request -> {
                    request.cancellationToken().onCancel(cancelled::countDown);
                    started.countDown();
                    try {
                        Thread.sleep(5000);
                        handlerCompleted.set(true);
                        request.text("should not reach");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));

        app.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/slow"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("timeout");
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handlerCompleted).isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void fastHandlerCompletesBeforeTimeout() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).requestTimeout(5),
                "request-timeout-fast",
                new RouteContribution("GET", "/fast", (BaseHandler) request -> {
                    request.text("ok");
                }));

        app.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/fast"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        } finally {
            app.stop();
        }
    }

    @Test
    void disabledTimeoutAllowsSlowHandler() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).requestTimeout(0),
                "request-timeout-disabled",
                new RouteContribution("GET", "/slow", (BaseHandler) request -> {
                    Thread.sleep(100);
                    request.text("completed");
                }));

        app.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/slow"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("completed");
        } finally {
            app.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
