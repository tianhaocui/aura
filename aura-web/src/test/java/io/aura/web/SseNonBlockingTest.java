package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;
import io.aura.port.SseEmitter;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response stream whose reader stops reading must not become the request worker's problem.
 * The old blocking output stream returned from the handler only after the socket accepted every
 * byte, so one slow reader could hold a worker indefinitely.
 */
class SseNonBlockingTest {

    private static final int EVENTS = 128;
    private static final String CHUNK = "x".repeat(16 * 1024);

    @Test
    void aClientThatNeverReadsDoesNotKeepTheHandlerOnTheWorker() throws Exception {
        int port = freePort();
        CountDownLatch finished = new CountDownLatch(1);
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port),
                "sse-slow-consumer",
                Route.of("GET", "/stream", (BaseHandler) ctx -> {
                    SseEmitter events = ctx.sse();
                    for (int i = 0; i < EVENTS; i++) events.send(CHUNK);
                    events.close();
                    finished.countDown();
                }));
        app.start();
        try (Socket socket = new Socket()) {
            // A small receive window makes the kernel stop accepting long before 2 MiB, which is
            // exactly the point: the handler must not be waiting for the reader.
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write((
                    "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertThat(finished.await(3, TimeUnit.SECONDS))
                    .as("the handler returned despite an unread response")
                    .isTrue();
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
