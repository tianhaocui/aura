package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClientResponseTest {

    @Test
    void header_returnsResponseHeader() {
        Router router = new Router();
        router.get("/ping", ctx -> {
            ctx.header("X-Custom", "hello");
            ctx.text("pong");
        });
        var client = TestApplications.client(router);

        var resp = client.get("/ping").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.header("X-Custom")).isEqualTo("hello");
    }

    @Test
    void header_returnsNullWhenNotSet() {
        Router router = new Router();
        router.get("/ping", ctx -> ctx.text("pong"));
        var client = TestApplications.client(router);

        var resp = client.get("/ping").execute();
        assertThat(resp.header("X-Missing")).isNull();
    }

    @Test
    void expectHeader_passesOnMatch() {
        Router router = new Router();
        router.get("/ping", ctx -> {
            ctx.header("X-Req-Id", "abc123");
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        client.get("/ping").execute()
                .expect(200)
                .expectHeader("X-Req-Id", "abc123");
    }

    @Test
    void expectHeader_throwsOnMismatch() {
        Router router = new Router();
        router.get("/ping", ctx -> {
            ctx.header("X-Req-Id", "abc");
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        assertThatThrownBy(() -> client.get("/ping").execute().expectHeader("X-Req-Id", "xyz"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("X-Req-Id");
    }

    @Test
    void jsonList_deserializesArray() {
        Router router = new Router();
        router.get("/users", ctx -> ctx.json(List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 30)
        )));
        var client = TestApplications.client(router);

        record User(String name, int age) {}
        var users = client.get("/users").execute().jsonList(User.class);
        assertThat(users).hasSize(2);
        assertThat(users.get(0).name()).isEqualTo("Alice");
        assertThat(users.get(1).age()).isEqualTo(30);
    }
}
