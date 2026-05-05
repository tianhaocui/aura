package io.aura.web;

import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.Post;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ServiceRegistrarTest {

    @Path("/item")
    static class ItemService {
        @Get("/search")
        public String search(String q) { return q; }

        public Object get(int id) { return id; }

        public List<String> list() { return List.of(); }
    }

    @Path("/order")
    static class OrderService {
        @Post("/submit")
        public String submit() { return "ok"; }
    }

    static class NoPrefixService {
        public List<String> list() { return List.of(); }
    }

    @Test
    void pathPrefix_isApplied() {
        Router router = new Router();
        ServiceRegistrar.register(new ItemService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.rawPath().startsWith("/item"));
    }

    @Test
    void getAnnotation_registeredAsGet() {
        Router router = new Router();
        ServiceRegistrar.register(new ItemService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals("/item/search"));
    }

    @Test
    void crudConvention_get_registeredAsGetWithIdParam() {
        Router router = new Router();
        ServiceRegistrar.register(new ItemService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r ->
                r.method().equals("GET") && r.rawPath().equals("/item/{id}"));
    }

    @Test
    void crudConvention_list_registeredAsGetWithNoParam() {
        Router router = new Router();
        ServiceRegistrar.register(new ItemService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r ->
                r.method().equals("GET") && r.rawPath().equals("/item"));
    }

    @Test
    void annotatedRoutes_registeredBeforeConventionRoutes() {
        Router router = new Router();
        ServiceRegistrar.register(new ItemService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        // /item/search must appear before /item/{id} so static wins over param
        int searchIdx = -1, paramIdx = -1;
        for (int i = 0; i < routes.size(); i++) {
            if ("/item/search".equals(routes.get(i).rawPath())) searchIdx = i;
            if ("/item/{id}".equals(routes.get(i).rawPath())) paramIdx = i;
        }
        assertThat(searchIdx).isGreaterThanOrEqualTo(0);
        assertThat(paramIdx).isGreaterThanOrEqualTo(0);
        assertThat(searchIdx).isLessThan(paramIdx);
    }

    @Test
    void noPathAnnotation_emptyPrefix() {
        Router router = new Router();
        ServiceRegistrar.register(new NoPrefixService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        // list() with no prefix → path is ""
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals(""));
    }
}
