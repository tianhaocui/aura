package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownCancellationTest {

    @Test
    void shutdownStopsNewHttpAdmissionBeforeDrainingActiveRequest() throws Exception {
        int port = freePort();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger newRequests = new AtomicInteger();
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).shutdownTimeout(5),
                "shutdown-admission",
                new RouteContribution("GET", "/wait", (BaseHandler) request -> {
                    request.cancellationToken().onCancel(cancelled::countDown);
                    started.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    request.text("done");
                }),
                new RouteContribution("GET", "/new", (BaseHandler) request -> {
                    newRequests.incrementAndGet();
                    request.text("unexpected");
                }));
        app.start();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> active = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/wait"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> stopped = CompletableFuture.runAsync(app::stop);
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();

            try {
                HttpResponse<String> rejected = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/new"))
                                .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(rejected.statusCode()).isNotEqualTo(200);
            } catch (java.io.IOException expected) {
                // Listener may reject by closing the connection instead of sending 503.
            }
            assertThat(newRequests).hasValue(0);
            release.countDown();
            stopped.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            active.cancel(true);
            app.stop();
        }
    }

    @Test
    void clientDisconnectCancelsActiveRequest() throws Exception {
        int port = freePort();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).shutdownTimeout(2),
                "disconnect-cancellation",
                new RouteContribution("GET", "/wait", (BaseHandler) request -> {
                    request.cancellationToken().onCancel(cancelled::countDown);
                    SseEmitter events = request.sse();
                    started.countDown();
                    try {
                        while (!request.cancellationToken().isCancelled()) {
                            events.send("heartbeat");
                            Thread.sleep(10);
                        }
                    } finally {
                        events.close();
                    }
                }));
        app.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write((
                    "GET /wait HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(socket.getInputStream().read()).isNotEqualTo(-1);
            socket.close();
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            app.stop();
        }
    }

    @Test
    void completedRequestDoesNotCancelRuntimeShutdownSignal() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port),
                "completed-request-cancellation",
                new RouteContribution("GET", "/ok",
                        (BaseHandler) request -> request.text("ok")));
        app.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(((io.aura.AuraWebRuntime) app.runtimeView())
                    .shutdownToken().isCancelled()).isFalse();
        } finally {
            app.stop();
        }
        assertThat(((io.aura.AuraWebRuntime) app.runtimeView())
                .shutdownToken().isCancelled()).isTrue();
    }

    @Test
    void shutdownCancelsActiveRequestBeforeGracefulDrainWaits() throws Exception {
        int port = freePort();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).shutdownTimeout(5),
                "shutdown-cancellation",
                new RouteContribution("GET", "/wait", (BaseHandler) request -> {
                    request.cancellationToken().onCancel(cancelled::countDown);
                    started.countDown();
                    while (!request.cancellationToken().isCancelled()) {
                        Thread.sleep(10);
                    }
                    request.status(503).text("stopping");
                }));
        app.start();
        CompletableFuture<HttpResponse<String>> response = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/wait"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> stopped = CompletableFuture.runAsync(app::stop);

            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            stopped.get(2, TimeUnit.SECONDS);
            assertThat(app.state()).isEqualTo(io.aura.contract.RuntimeState.STOPPED);
            try {
                response.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ignored) {
                // The listener may close before the cooperative response reaches the client.
            }
        } finally {
            app.stop();
            response.cancel(true);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
