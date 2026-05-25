package io.aura.web;

import io.aura.annotation.*;
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
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals(""));
    }

    // --- Put / Delete annotations ---

    @Path("/resource")
    static class PutDeleteService {
        @Put("/update/{id}")
        public String update(int id) { return "updated-" + id; }

        @Delete("/remove/{id}")
        public void remove(int id) {}
    }

    @Test
    void putAnnotation_registeredAsPut() {
        Router router = new Router();
        ServiceRegistrar.register(new PutDeleteService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("PUT") && r.rawPath().equals("/resource/update/{id}"));
    }

    @Test
    void deleteAnnotation_registeredAsDelete() {
        Router router = new Router();
        ServiceRegistrar.register(new PutDeleteService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("DELETE") && r.rawPath().equals("/resource/remove/{id}"));
    }

    // --- Convention methods: create, update, delete ---

    @Path("/user")
    static class FullCrudService {
        public Object get(int id) { return id; }
        public List<String> list() { return List.of(); }
        public String create(String body) { return "created"; }
        public String update(long id) { return "updated"; }
        public void delete(int id) {}
    }

    @Test
    void crudConvention_create_registeredAsPost() {
        Router router = new Router();
        ServiceRegistrar.register(new FullCrudService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("POST") && r.rawPath().equals("/user"));
    }

    @Test
    void crudConvention_update_registeredAsPutWithParam() {
        Router router = new Router();
        ServiceRegistrar.register(new FullCrudService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("PUT") && r.rawPath().contains("/user/{"));
    }

    @Test
    void crudConvention_delete_registeredAsDeleteWithParam() {
        Router router = new Router();
        ServiceRegistrar.register(new FullCrudService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("DELETE") && r.rawPath().contains("/user/{"));
    }

    // --- @Desc annotation ---

    @Path("/doc")
    static class DescService {
        @Get("/info")
        @Desc("Get info about the system")
        public String info() { return "info"; }
    }

    @Test
    void descAnnotation_doesNotPreventRegistration() {
        Router router = new Router();
        ServiceRegistrar.register(new DescService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals("/doc/info"));
    }

    // --- pathParamSegment with different types ---

    @Path("/typed")
    static class LongParamService {
        public String get(long id) { return "long-" + id; }
    }

    @Path("/typed2")
    static class StringParamService {
        public String get(String slug) { return "slug-" + slug; }
    }

    @Path("/typed3")
    static class NoParamGetService {
        public String get() { return "no-param"; }
    }

    @Test
    void pathParamSegment_longType_usesParamName() {
        Router router = new Router();
        ServiceRegistrar.register(new LongParamService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().contains("{"));
    }

    @Test
    void pathParamSegment_stringType_usesParamName() {
        Router router = new Router();
        ServiceRegistrar.register(new StringParamService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().contains("{"));
    }

    @Test
    void pathParamSegment_noParam_defaultsToId() {
        Router router = new Router();
        ServiceRegistrar.register(new NoParamGetService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).anyMatch(r -> r.method().equals("GET") && r.rawPath().equals("/typed3/{id}"));
    }

    // --- private methods are skipped ---

    @Path("/priv")
    static class PrivateMethodService {
        public List<String> list() { return List.of("a"); }
        @SuppressWarnings("unused")
        private String secret() { return "hidden"; }
    }

    @Test
    void privateMethod_notRegistered() {
        Router router = new Router();
        ServiceRegistrar.register(new PrivateMethodService(), router);
        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).method()).isEqualTo("GET");
    }
}
