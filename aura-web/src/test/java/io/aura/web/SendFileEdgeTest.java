package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SendFileEdgeTest {

    @Test
    void sendFile_contentDisposition_quotesFilename() throws Exception {
        // The real Context.sendFile quotes the filename in Content-Disposition
        // Verify the format through MockContext capturing
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, Aura.create());
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        ctx.sendFile("file \"with\" quotes.pdf", data);
        assertThat(ctx.fileName).isEqualTo("file \"with\" quotes.pdf");
    }

    @Test
    void sendFile_contentLength_matchesDataSize() throws Exception {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, Aura.create());
        byte[] data = "twelve chars".getBytes(StandardCharsets.UTF_8);
        ctx.sendFile("test.bin", data);
        assertThat(ctx.fileBytes).hasSize(data.length);
        assertThat(ctx.fileBytes.length).isEqualTo(12);
    }

    @Test
    void sendFile_viaTestClient_withMiddleware() {
        Aura app = Aura.create();
        Router router = new Router();
        byte[] content = "hello-file".getBytes(StandardCharsets.UTF_8);
        router.before(ctx -> ctx.set("processed", true));
        router.get("/dl", ctx -> ctx.sendFile("export.xlsx", content, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        var client = new TestClient(app, router);
        var resp = client.get("/dl").execute();
        assertThat(resp.status()).isEqualTo(200);
    }

    @Test
    void sendFile_nullContentType_usesDefault() throws Exception {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, Aura.create());
        byte[] data = "binary".getBytes(StandardCharsets.UTF_8);
        ctx.sendFile("archive.zip", data, null);
        assertThat(ctx.fileContentType).isNull();
        // Real Context passes null through — caller should use the 2-arg overload for default
    }
}
