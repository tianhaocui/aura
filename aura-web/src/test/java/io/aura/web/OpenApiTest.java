package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class OpenApiTest {

    @Test
    void disabled_returns404() {
        Aura app = Aura.create();
        app.get("/hello", (Supplier<String>) () -> "hi");
        var client = TestClient.of(app);
        assertThat(client.get("/openapi.json").execute().status()).isEqualTo(404);
    }

    @Test
    void enabled_returnsValidStructure() {
        Aura app = Aura.create().openapi(true);
        app.get("/hello", (Supplier<String>) () -> "hi");
        var client = TestClient.of(app);
        var resp = client.get("/openapi.json").execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject doc = JSON.parseObject(resp.body());
        assertThat(doc.getString("openapi")).isEqualTo("3.0.3");
        assertThat(doc.getJSONObject("info")).isNotNull();
        assertThat(doc.getJSONObject("paths")).isNotNull();
        assertThat(doc.getJSONObject("paths").containsKey("/hello")).isTrue();
    }

    @Test
    void customTitle() {
        Aura app = Aura.create().openapi("My API");
        app.get("/x", (Supplier<String>) () -> "x");
        var client = TestClient.of(app);
        JSONObject doc = JSON.parseObject(client.get("/openapi.json").execute().body());
        assertThat(doc.getJSONObject("info").getString("title")).isEqualTo("My API");
    }

    @Path("/api")
    public static class UserController {
        @Get("/users/{id}")
        public UserResp getUser(int id) { return new UserResp("Alice", 25); }

        @Post("/users")
        public UserResp createUser(CreateUserReq req) { return new UserResp(req.name(), req.age()); }
    }

    public record CreateUserReq(@NotBlank String name, @Min(0) @Max(150) int age) {}
    public record UserResp(String name, int age) {}

    @Test
    void pathParams_inParameters() {
        Aura app = Aura.create().openapi(true);
        app.service(new UserController());
        var client = TestClient.of(app);
        JSONObject doc = JSON.parseObject(client.get("/openapi.json").execute().body());

        JSONObject pathItem = doc.getJSONObject("paths").getJSONObject("/api/users/{id}");
        assertThat(pathItem).isNotNull();
        var params = pathItem.getJSONObject("get").getJSONArray("parameters");
        assertThat(params).isNotNull();
        assertThat(params.getJSONObject(0).getString("name")).isEqualTo("id");
        assertThat(params.getJSONObject(0).getString("in")).isEqualTo("path");
        assertThat(params.getJSONObject(0).getBoolean("required")).isTrue();
    }

    @Test
    void recordBody_generatesSchema() {
        Aura app = Aura.create().openapi(true);
        app.service(new UserController());
        var client = TestClient.of(app);
        JSONObject doc = JSON.parseObject(client.get("/openapi.json").execute().body());

        JSONObject postOp = doc.getJSONObject("paths").getJSONObject("/api/users").getJSONObject("post");
        assertThat(postOp.getJSONObject("requestBody")).isNotNull();

        JSONObject schemas = doc.getJSONObject("components").getJSONObject("schemas");
        assertThat(schemas.containsKey("CreateUserReq")).isTrue();
        JSONObject reqSchema = schemas.getJSONObject("CreateUserReq");
        assertThat(reqSchema.getString("type")).isEqualTo("object");
        assertThat(reqSchema.getJSONObject("properties").containsKey("name")).isTrue();
        assertThat(reqSchema.getJSONObject("properties").containsKey("age")).isTrue();
    }

    @Test
    void validationAnnotations_markRequired() {
        Aura app = Aura.create().openapi(true);
        app.service(new UserController());
        var client = TestClient.of(app);
        JSONObject doc = JSON.parseObject(client.get("/openapi.json").execute().body());

        JSONObject reqSchema = doc.getJSONObject("components").getJSONObject("schemas").getJSONObject("CreateUserReq");
        assertThat(reqSchema.getJSONArray("required")).contains("name");

        JSONObject ageProps = reqSchema.getJSONObject("properties").getJSONObject("age");
        assertThat(ageProps.getLong("minimum")).isEqualTo(0L);
        assertThat(ageProps.getLong("maximum")).isEqualTo(150L);
    }

    @Test
    void returnType_inResponse() {
        Aura app = Aura.create().openapi(true);
        app.service(new UserController());
        var client = TestClient.of(app);
        JSONObject doc = JSON.parseObject(client.get("/openapi.json").execute().body());

        JSONObject getOp = doc.getJSONObject("paths").getJSONObject("/api/users/{id}").getJSONObject("get");
        JSONObject resp200 = getOp.getJSONObject("responses").getJSONObject("200");
        assertThat(resp200.getJSONObject("content")).isNotNull();

        JSONObject schemas = doc.getJSONObject("components").getJSONObject("schemas");
        assertThat(schemas.containsKey("UserResp")).isTrue();
    }
}
