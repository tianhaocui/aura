package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AuraBuilderTest {

    // --- port ---

    @Test
    void port_setsAndReads() {
        Aura app = Aura.create().port(9090);
        assertThat(app.port()).isEqualTo(9090);
    }

    // --- env ---

    @Test
    void env_setsAndReads() {
        Aura app = Aura.create().env("prod");
        assertThat(app.env()).isEqualTo("prod");
    }

    // --- workers ---

    @Test
    void workers_setsAndReads() {
        Aura app = Aura.create().workers(50);
        assertThat(app.workers()).isEqualTo(50);
    }

    // --- prop(key, value) / prop(key) ---

    @Test
    void prop_setsAndReads() {
        Aura app = Aura.create().prop("db.url", "jdbc:h2:mem:test");
        assertThat(app.prop("db.url")).isEqualTo("jdbc:h2:mem:test");
    }

    @Test
    void prop_returnsNullForUnsetKey() {
        Aura app = Aura.create();
        assertThat(app.prop("nonexistent.key.xyz")).isNull();
    }

    // --- prop(key, defaultInt) ---

    @Test
    void propWithDefault_returnsDefaultWhenNotSet() {
        Aura app = Aura.create();
        assertThat(app.prop("missing.int.key", 42)).isEqualTo(42);
    }

    @Test
    void propWithDefault_returnsParsedValueWhenSet() {
        Aura app = Aura.create().prop("pool.size", "10");
        assertThat(app.prop("pool.size", 5)).isEqualTo(10);
    }

    // --- register / get ---

    @Test
    void register_andGetByExactType() {
        Aura app = Aura.create();
        StringBuilder sb = new StringBuilder("hello");
        app.register(sb);
        assertThat(app.getBean(StringBuilder.class)).isSameAs(sb);
    }

    @Test
    void get_returnsNullForUnregisteredType() {
        Aura app = Aura.create();
        assertThat(app.getBean(StringBuilder.class)).isNull();
    }

    @Test
    void get_resolvesByAssignability() {
        Aura app = Aura.create();
        // Register a concrete type, retrieve by interface
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        app.register(list);
        assertThat(app.getBean(java.util.List.class)).isSameAs(list);
    }

    // --- named registry ---

    @Test
    void registerNamed_andGetByName() {
        Aura app = Aura.create();
        StringBuilder main = new StringBuilder("main");
        StringBuilder log = new StringBuilder("log");
        app.register("main", main).register("log", log);
        assertThat(app.getBean("main", StringBuilder.class)).isSameAs(main);
        assertThat(app.getBean("log", StringBuilder.class)).isSameAs(log);
    }

    @Test
    void getNamed_returnsNullForUnregisteredName() {
        Aura app = Aura.create();
        assertThat(app.getBean("nope", StringBuilder.class)).isNull();
    }

    @Test
    void getNamed_returnsNullForTypeMismatch() {
        Aura app = Aura.create();
        app.register("x", "hello");
        assertThat(app.getBean("x", Integer.class)).isNull();
    }

    // --- cors ---

    @Test
    void cors_trueSetsCorsOriginToWildcard() {
        Aura app = Aura.create().cors(true);
        assertThat(app.corsOrigin()).isEqualTo("*");
    }

    @Test
    void cors_falseSetsCorsOriginToNull() {
        Aura app = Aura.create().cors(false);
        assertThat(app.corsOrigin()).isNull();
    }

    @Test
    void cors_specificOriginSetsValue() {
        Aura app = Aura.create().cors("https://example.com");
        assertThat(app.corsOrigin()).isEqualTo("https://example.com");
    }

    // --- maxBodySize ---

    @Test
    void maxBodySize_setsValue() {
        Aura app = Aura.create().maxBodySize(5 * 1024 * 1024L);
        assertThat(app.maxBodySize()).isEqualTo(5 * 1024 * 1024L);
    }

    // --- shutdownTimeout ---

    @Test
    void shutdownTimeout_setsValue() {
        Aura app = Aura.create().shutdownTimeout(60);
        assertThat(app.shutdownTimeout()).isEqualTo(60);
    }

    // --- direct routes ---

    @Test
    void get_addsRouteEntry() {
        Aura app = Aura.create().get("/hello", (Object) null);
        assertThat(app.directRoutes()).hasSize(1);
        RouteEntry entry = app.directRoutes().get(0);
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.path()).isEqualTo("/hello");
    }

    @Test
    void post_addsRouteEntry() {
        Aura app = Aura.create().post("/users", (Object) null);
        assertThat(app.directRoutes()).hasSize(1);
        assertThat(app.directRoutes().get(0).method()).isEqualTo("POST");
    }

    @Test
    void put_addsRouteEntry() {
        Aura app = Aura.create().put("/users/1", (Object) null);
        assertThat(app.directRoutes()).hasSize(1);
        assertThat(app.directRoutes().get(0).method()).isEqualTo("PUT");
    }

    @Test
    void delete_addsRouteEntry() {
        Aura app = Aura.create().delete("/users/1", (Object) null);
        assertThat(app.directRoutes()).hasSize(1);
        assertThat(app.directRoutes().get(0).method()).isEqualTo("DELETE");
    }

    @Test
    void multipleRoutes_accumulateInOrder() {
        Aura app = Aura.create()
                .get("/a", (Object) null)
                .post("/b", (Object) null)
                .put("/c", (Object) null);
        assertThat(app.directRoutes()).hasSize(3);
        assertThat(app.directRoutes().get(0).path()).isEqualTo("/a");
        assertThat(app.directRoutes().get(1).path()).isEqualTo("/b");
        assertThat(app.directRoutes().get(2).path()).isEqualTo("/c");
    }

    // --- service ---

    @Test
    void service_addsToServicesList() {
        Object svc1 = new Object();
        Object svc2 = new Object();
        Aura app = Aura.create().service(svc1, svc2);
        assertThat(app.services()).containsExactly(svc1, svc2);
    }

    @Test
    void service_multipleCallsAccumulate() {
        Object svc1 = new Object();
        Object svc2 = new Object();
        Aura app = Aura.create().service(svc1).service(svc2);
        assertThat(app.services()).containsExactly(svc1, svc2);
    }
}
