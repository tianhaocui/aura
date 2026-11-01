package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.annotation.Desc;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.Post;
import io.aura.contract.ApiParameter;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.Route;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

/** TEMPORARY Stage2-B probe: dumps /__schema__ + OpenAPI output for byte comparison. */
class SchemaProbeTest {
    @Test
    void dumpSchemaAndOpenApi() throws Exception {
        StringBuilder out = new StringBuilder();

        // Module-contributed routes carrying the full metadata set.
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("probe", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.route(Route.of("GET", "/probe/orders/{id}",
                        (BaseHandler) ctx -> ctx.text("ok"))
                        .withParams(java.util.List.of(
                                new ApiParameter("id", "long", "path", true, "Order id"),
                                new ApiParameter("q", "string", "query", false, "Search")))
                        .withReturnType("Order")
                        .withResponseSchema(java.util.Map.of("type", "object"))
                        .describedAs("Get an order")
                        .requireAuth("orders.read")
                        .withStreaming(true));
                context.route(Route.of("POST", "/probe/orders",
                        (BaseHandler) ctx -> ctx.text("created")).describedAs("Create an order"));
                context.route(Route.of("DELETE", "/probe/minimal",
                        (BaseHandler) ctx -> ctx.text("gone")));
            }
        });
        out.append("MODULE_SCHEMA=").append(JSON.toJSONString(TestClient.of(app).schema())).append('\n');
        out.append("MODULE_OPENAPI=").append(JSON.toJSONString(TestClient.of(app).openApi())).append('\n');

        // Fluent router routes: explicit ApiParameter contract + name/description overlay.
        Router router = new Router();
        router.get("/orders/{id}", ctx -> ctx.text("ok"))
                .describe("Get order")
                .param("id", "long", "path", true, "Order identifier");
        router.get("/people/{name}", ctx -> ctx.text("ok"))
                .describe("Lookup person")
                .param("name", "Person name");
        router.post("/drafts", ctx -> ctx.text("ok"))
                .describe("Create draft")
                .streaming();
        router.get("/service/{id}", new ProbeService(), "get").describe("Service get");
        router.group("/nested", group -> group.get("/items/{id}", ctx -> ctx.text("ok"))
                .describe("Nested item")
                .param("id", "string", "path", true, "Nested identifier"));
        @SuppressWarnings("unchecked")
        var raw = TestApplications.client(router).schema();
        out.append("ROUTER_SCHEMA=").append(JSON.toJSONString(raw)).append('\n');
        out.append("ROUTER_OPENAPI=").append(JSON.toJSONString(TestApplications.client(router).openApi())).append('\n');

        Files.writeString(java.nio.file.Path.of("/tmp/aura-schema-probe.json"), out.toString());
    }

    @Path("/probe-service")
    static final class ProbeService {
        @Get("/{id}")
        public Item get(@Desc("Item identifier") long id) { return new Item(id, "name"); }

        @Post("/search")
        public java.util.List<Item> search(String term) { return java.util.List.of(); }
    }

    record Item(long id, String name) { }
}
