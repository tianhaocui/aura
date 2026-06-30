package io.aura.web;

import io.aura.Aura;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ParamResolverCustomTest {

    public record CurrentUser(String id, String name) {}

    @Path("/api")
    public static class UserController {
        @Get("/me")
        public CurrentUser me(CurrentUser user) { return user; }

        @Get("/greeting")
        public String greeting(CurrentUser user, String name) {
            return "Hello " + name + ", you are " + user.name();
        }
    }

    @Test
    void customResolver_injectsValue() {
        Aura app = Aura.create()
                .paramResolver(CurrentUser.class, ctx -> new CurrentUser("u1", "Alice"));
        app.service(new UserController());

        var client = TestClient.of(app);
        var resp = client.get("/api/me").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("\"id\":\"u1\"");
        assertThat(resp.body()).contains("\"name\":\"Alice\"");
    }

    @Test
    void customResolver_mixedWithNormalParams() {
        Aura app = Aura.create()
                .paramResolver(CurrentUser.class, ctx -> new CurrentUser("u2", "Bob"));
        app.service(new UserController());

        var client = TestClient.of(app);
        var resp = client.get("/api/greeting").query("name", "World").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("Hello World, you are Bob");
    }

    @Test
    void noResolver_fallsBackToDefault() {
        Aura app = Aura.create();
        app.service(new UserController());

        var client = TestClient.of(app);
        // Without resolver, CurrentUser is treated as body (Record) → null since no body
        var resp = client.get("/api/me").execute();
        assertThat(resp.status()).isEqualTo(200);
    }
}
