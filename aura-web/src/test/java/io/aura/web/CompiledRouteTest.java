package io.aura.web;

import io.aura.port.BaseHandler;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CompiledRouteTest {

    private static final BaseHandler NOOP = ctx -> { };

    private static CompiledRoute compile(String method, String path) {
        return CompiledRoute.compile("", Route.of(method, path, NOOP), List.of(), NOOP, List.of(), Map.of());
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
    void staticPathTreatsRegexMetacharactersAsLiterals() {
        CompiledRoute route = compile("GET", "/reports/v1.0+final");

        assertThat(route.match("/reports/v1.0+final")).isNotNull();
        assertThat(route.match("/reports/v1x000final")).isNull();
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
        CompiledRoute route = CompiledRoute.compile("/api", Route.of("GET", "/user/{id}", NOOP),
                List.of(), NOOP, List.of(), Map.of());
        assertThat(route.rawPath()).isEqualTo("/api/user/{id}");
    }

    @Test
    void aParameterIsHandedToTheHandlerDecoded() {
        CompiledRoute route = compile("GET", "/user/{name}");

        assertThat(route.match("/user/ada%20lint")).containsEntry("name", "ada lint");
        assertThat(route.match("/user/%E5%8C%BF%E5%90%8D")).containsEntry("name", "匿名");
    }

    @Test
    void anEncodedSlashIsNotASecondSegment() {
        CompiledRoute route = compile("GET", "/user/{id}");

        // The raw form holds no slash, so the wildcard would take it, and a handler that joins the
        // value into a path would be handed two segments where the route promised one.
        assertThat(route.match("/user/a%2Fb")).isNull();
        assertThat(route.match("/user/a%2fb")).isNull();
    }

    @Test
    void aSegmentThatWasNeverEncodedIsNotInvented() {
        CompiledRoute route = compile("GET", "/user/{id}");

        assertThat(route.match("/user/50%off")).containsEntry("id", "50%off");
    }

    @Test
    void aStaticPathIsMatchedAfterTheRequestIsDecoded() {
        CompiledRoute route = compile("GET", "/files/a b");

        assertThat(route.match("/files/a%20b")).isNotNull();
        assertThat(route.match("/files/a b")).isNotNull();
    }

    @Test
    void aStaticPathCanContainDecodedUnicode() {
        CompiledRoute route = compile("GET", "/文件");

        assertThat(route.match("/%E6%96%87%E4%BB%B6")).isNotNull();
    }

    @Test
    void aBackslashOrMalformedUtf8IsNotASegment() {
        CompiledRoute route = compile("GET", "/user/{id}");

        assertThat(route.match("/user/a%5Cb")).isNull();
        assertThat(route.match("/user/%FF")).isNull();
        assertThat(route.match("/user/%00")).isNull();
    }

    @Test
    void aDoubleEncodedSlashIsDecodedOnlyOnce() {
        CompiledRoute route = compile("GET", "/user/{id}");

        // %252F names the literal text "%2F", not a slash; decoding it twice would turn a value
        // the client sent into a path the route never described.
        assertThat(route.match("/user/a%252Fb")).containsEntry("id", "a%2Fb");
    }
}
