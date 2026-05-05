package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CompiledRouteTest {

    private static CompiledRoute compile(String method, String path) {
        return CompiledRoute.compile(method, "", path, List.of(), ctx -> {}, List.of(), null);
    }

    @Test
    void staticPath_matchesExact() {
        CompiledRoute route = compile("GET", "/hello");
        assertThat(route.match("/hello")).isNotNull();
    }

    @Test
    void staticPath_doesNotMatchDifferentPath() {
        CompiledRoute route = compile("GET", "/hello");
        assertThat(route.match("/world")).isNull();
    }

    @Test
    void paramPath_matchesAndExtractsParam() {
        CompiledRoute route = compile("GET", "/user/{id}");
        Map<String, String> params = route.match("/user/42");
        assertThat(params).isNotNull();
        assertThat(params).containsEntry("id", "42");
    }

    @Test
    void paramPath_doesNotMatchExtraSegments() {
        CompiledRoute route = compile("GET", "/user/{id}");
        assertThat(route.match("/user/42/extra")).isNull();
    }

    @Test
    void multiParam_extractsBoth() {
        CompiledRoute route = compile("GET", "/user/{id}/post/{postId}");
        Map<String, String> params = route.match("/user/7/post/99");
        assertThat(params).isNotNull();
        assertThat(params).containsEntry("id", "7");
        assertThat(params).containsEntry("postId", "99");
    }

    @Test
    void rawPath_preservesOriginalPattern() {
        CompiledRoute route = compile("GET", "/user/{id}");
        assertThat(route.rawPath()).isEqualTo("/user/{id}");
    }

    @Test
    void prefixIsIncludedInRawPath() {
        CompiledRoute route = CompiledRoute.compile("GET", "/api", "/user/{id}",
                List.of(), ctx -> {}, List.of(), null);
        assertThat(route.rawPath()).isEqualTo("/api/user/{id}");
    }
}
