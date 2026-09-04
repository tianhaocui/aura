package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RuntimeWebFoundationTest {
    @Test void paginationRejectsInvalidAndOversizedInput() {
        Aura app = Aura.create().configure(c -> c.pageSizeMax(100));
        Router router = new Router();
        router.get("/page", ctx -> ctx.text(ctx.pageNum() + ":" + ctx.pageSize()));
        TestClient client = new TestClient(app, router);
        assertThat(client.get("/page?page=0").execute().status()).isEqualTo(400);
        assertThat(client.get("/page?pageSize=101").execute().status()).isEqualTo(400);
        assertThat(client.get("/page?page=2&pageSize=100").execute().body()).isEqualTo("2:100");
    }

    @Test void contextExposesCancellationToken() {
        MockContext ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, Aura.create());
        assertThat(ctx.cancellationToken()).isNotNull();
        assertThat(ctx.isCancelled()).isFalse();
        ctx.cancellationToken().cancel();
        assertThat(ctx.isCancelled()).isTrue();
    }
}
