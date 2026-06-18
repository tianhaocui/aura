package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortConflictTest {

    @Test
    void startOnOccupiedPort_throwsFriendlyError() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            var app = Aura.create()
                .port(port)
                .routes((BaseRouter r) -> r.get("/", ctx -> ctx.text("hi")));

            assertThatThrownBy(app::start)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Port " + port + " is already in use")
                .hasMessageContaining("AURA_PORT")
                .hasCauseInstanceOf(java.net.BindException.class);
        }
    }
}
