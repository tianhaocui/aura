package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawResponseTest {

    @Test
    void raw_sendsBodyWithoutSettingContentType() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/csv", ctx -> {
                ctx.header("Content-Type", "text/csv; charset=utf-8");
                ctx.raw("a,b,c\n1,2,3");
            }));
        var client = TestClient.of(app);

        var resp = client.get("/csv").expect(200);
        assertThat(resp.body()).isEqualTo("a,b,c\n1,2,3");
        assertThat(resp.header("Content-Type")).isEqualTo("text/csv; charset=utf-8");
    }

    @Test
    void raw_withoutExplicitContentType() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/data", ctx -> ctx.raw("binary-ish")));
        var client = TestClient.of(app);

        var resp = client.get("/data").expect(200);
        assertThat(resp.body()).isEqualTo("binary-ish");
    }

    @Test
    void raw_doesNotOverridePresetHeader() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/xml", ctx -> {
                ctx.header("Content-Type", "application/xml");
                ctx.raw("<root/>");
            }));
        var client = TestClient.of(app);

        var resp = client.get("/xml").expect(200);
        assertThat(resp.body()).isEqualTo("<root/>");
        assertThat(resp.header("Content-Type")).isEqualTo("application/xml");
    }
}
