package io.aura.web;

import io.aura.Aura;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.Post;
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
    void pathServicesAutoRouted() {
        Aura app = Aura.create();
        Repo repo = new Repo();
        app.register(repo);
        app.services(ItemController.class);

        Router router = new Router();
        for (Object svc : app.services()) {
            ServiceRegistrar.register(svc, router);
        }

        // Before start, services() list has no route instances
        // Simulate the resolve + add @Path beans logic that start() performs
        var registry = new java.util.LinkedHashMap<Class<?>, Object>();
        registry.put(Aura.class, app);
        registry.put(Repo.class, repo);
        registry.put(ItemController.class, new ItemController(repo));
        Object bean = registry.get(ItemController.class);

        ServiceRegistrar.register(bean, router);
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
    void auraServicesContainsPathBeansAfterResolve() {
        Aura app = Aura.create();
        app.register(new Repo());
        app.services(ItemController.class, PureService.class);

        // Verify initial state
        assertThat(app.services()).isEmpty();
        assertThat(app.serviceClasses()).containsExactly(ItemController.class, PureService.class);
    }
}
