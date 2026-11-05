package io.aura.web;

import io.aura.contract.Route;
import io.aura.port.WsHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledWsRouteTest {

    private static CompiledWsRoute compile(String path) {
        return CompiledWsRoute.compile(Route.webSocket(path, new WsHandler()), List.of(),
                1024L, new WebSocketConnectionRegistry(4, 30));
    }

    @Test
    void aWebSocketTemplateIsAPathNotARegexThatHappensToReadLikeOne() {
        CompiledWsRoute route = compile("/ws/room(s)");

        assertThat(route.match("/ws/room(s)")).isNotNull();
        // Only . and + were escaped, so (s) was a group: this path reached the endpoint while the
        // one the author declared could not.
        assertThat(route.match("/ws/rooms")).isNull();
    }

    @Test
    void aWebSocketParameterIsDecodedLikeAnHttpOne() {
        CompiledWsRoute route = compile("/ws/room/{roomId}");

        assertThat(route.match("/ws/room/ada%20lint")).containsEntry("roomId", "ada lint");
        assertThat(route.match("/ws/room/a%2Fb")).isNull();
    }

    @Test
    void twoNamesForOneWebSocketTemplateAreRejected() {
        CompiledWsRoute first = compile("/ws/{id}");
        CompiledWsRoute second = compile("/ws/{name}");

        assertThatThrownBy(() -> UndertowStarter.detectDuplicateWsRoutes(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/ws/{id}")
                .hasMessageContaining("/ws/{name}");
    }

    @Test
    void theSameWebSocketTemplateTwiceIsRejected() {
        CompiledWsRoute first = compile("/ws/{id}");
        CompiledWsRoute second = compile("/ws/{id}");

        assertThatThrownBy(() -> UndertowStarter.detectDuplicateWsRoutes(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate WebSocket route: /ws/{id}");
    }
}
