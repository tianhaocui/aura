package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SendFileTest {

    private static Aura app() { return Aura.create(); }

    @Test
    void sendFile_defaultContentType() throws Exception {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, app());
        byte[] data = "hello pdf".getBytes(StandardCharsets.UTF_8);

        ctx.sendFile("report.pdf", data);

        assertThat(ctx.fileName).isEqualTo("report.pdf");
        assertThat(ctx.fileBytes).isEqualTo(data);
        assertThat(ctx.fileContentType).isEqualTo("application/octet-stream");
    }

    @Test
    void sendFile_customContentType() throws Exception {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, app());
        byte[] data = "col1,col2\na,b".getBytes(StandardCharsets.UTF_8);

        ctx.sendFile("data.csv", data, "text/csv");

        assertThat(ctx.fileName).isEqualTo("data.csv");
        assertThat(ctx.fileBytes).isEqualTo(data);
        assertThat(ctx.fileContentType).isEqualTo("text/csv");
    }

    @Test
    void sendFile_emptyData() throws Exception {
        var ctx = new MockContext(Map.of(), Map.of(), Map.of(), null, app());
        byte[] data = new byte[0];

        ctx.sendFile("empty.bin", data);

        assertThat(ctx.fileBytes).isEmpty();
        assertThat(ctx.fileName).isEqualTo("empty.bin");
    }

    @Test
    void sendFile_viaTestClient() {
        Aura app = Aura.create();
        Router router = new Router();
        byte[] content = "file-content".getBytes(StandardCharsets.UTF_8);
        router.get("/download", ctx -> ctx.sendFile("test.txt", content, "text/plain"));

        var client = new TestClient(app, router);
        var resp = client.get("/download").execute();
        assertThat(resp.status()).isEqualTo(200);
    }
}
