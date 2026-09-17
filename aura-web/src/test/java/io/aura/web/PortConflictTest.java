package io.aura.web;

import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortConflictTest {

    @Test
    void startOnOccupiedPort_throwsFriendlyError() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            var app = TestApplications.app(
                    new AuraConfig().port(port),
                    "port-conflict",
                    new RouteContribution("GET", "/", (BaseHandler) ctx -> ctx.text("hi")));

            assertThatThrownBy(app::start)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Port " + port + " is already in use")
                .hasMessageContaining("AURA_PORT")
                .hasCauseInstanceOf(java.net.BindException.class);
        }
    }
}
