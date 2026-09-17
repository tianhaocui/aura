package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.Post;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServicesPathRoutingTest {

    public static class Repo {
        public String find(int id) { return "item-" + id; }
    }

    @Path("/api/items")
    public static class ItemController {
        private final Repo repo;
        public ItemController(Repo repo) { this.repo = repo; }

        @Get("/{id}")
        public String get(int id) { return repo.find(id); }

        @Post("")
        public String create(String body) { return "created"; }
    }

    @Test
    void pathServiceRegistrationProducesAnnotatedRoutes() {
        Repo repo = new Repo();
        Router router = new Router();
        ServiceRegistrar.register(new ItemController(repo), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);

        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals("/api/items/{id}"));
        assertThat(routes).anyMatch(r -> r.method().equals("POST") && r.rawPath().equals("/api/items"));
    }

    public static class PureService {
        public String compute() { return "result"; }
    }

    @Test
    void nonPathServiceNotRouted() {
        Router router = new Router();
        PureService svc = new PureService();
        ServiceRegistrar.register(svc, router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        // PureService has no @Path, no CRUD convention methods → no routes
        assertThat(routes).isEmpty();
    }

    @Test
    void explicitModulePublishesPathServiceWithItsDependency() {
        AuraApp app = AuraApp.create().use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("items", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.register(new Repo());
                context.service(new ItemController(context.require(Repo.class)));
            }
        });

        TestClient.Response response = TestClient.of(app).get("/api/items/7").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("item-7");
    }
}
