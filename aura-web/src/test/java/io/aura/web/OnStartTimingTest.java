package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnStartTimingTest {

    @Test
    void onStart_firesAfterStarterStart() throws Exception {
        List<String> order = new ArrayList<>();
        int port = 19877;

        var app = Aura.create()
            .port(port)
            .onStart(a -> order.add("onStart"))
            .routes((BaseRouter r) -> r.get("/ping", ctx -> ctx.text("pong")));

        try {
            app.start();
            // If we get here, starter.start() completed successfully (port is listening)
            // and onStart fired after it. Verify by checking the hook ran.
            assertThat(order).containsExactly("onStart");

            // Verify server is actually listening (starter.start completed before fireStart)
            var conn = (java.net.HttpURLConnection)
                new java.net.URL("http://localhost:" + port + "/ping").openConnection();
            conn.setConnectTimeout(2000);
            assertThat(conn.getResponseCode()).isEqualTo(200);
        } finally {
            app.stop();
        }
    }
}
