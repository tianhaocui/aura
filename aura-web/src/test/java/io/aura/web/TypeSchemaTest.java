package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSchemaTest {
    record Address(String city, int zip) { }
    record CreateUser(String name, Address address, boolean active) { }
    record BatchRequest(List<Address> addresses) { }

    @Test
    void recordBodyProducesNestedSchemaWithPrimitiveRequiredFields() {
        Map<String, Object> schema = TypeSchema.describe(CreateUser.class);

        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked") Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("name", "address", "active");
        assertThat(schema.get("required")).asList().containsExactly("name", "address", "active");
        @SuppressWarnings("unchecked") Map<String, Object> address = (Map<String, Object>) properties.get("address");
        assertThat(address.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked") Map<String, Object> addressProperties = (Map<String, Object>) address.get("properties");
        assertThat(addressProperties.keySet()).contains("city", "zip");
        assertThat(address.get("required")).asList().containsExactly("city", "zip");
    }

    @Test
    void openApiUsesBodySchemaFromSharedParameter() {
        Map<String, Object> schema = TypeSchema.describe(CreateUser.class);
        ApiRoute route = new ApiRoute("POST", "/users", "Create user", "CreateUser",
                List.of(new ApiParameter("request", "CreateUser", "body", true, null, schema)), null);

        Map<String, Object> document = OpenApiGenerator.generate("Users", "1.0.0", List.of(route));
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get("/users")).get("post");
        Map<?, ?> requestBody = (Map<?, ?>) operation.get("requestBody");
        Map<?, ?> content = (Map<?, ?>) requestBody.get("content");
        Map<?, ?> bodySchema = (Map<?, ?>) ((Map<?, ?>) content.get("application/json")).get("schema");
        @SuppressWarnings("unchecked") Map<String, Object> properties = (Map<String, Object>) bodySchema.get("properties");
        assertThat(properties.keySet()).contains("name", "address", "active");
    }

    @Test
    void genericCollectionBodyIncludesElementSchema() {
        Map<String, Object> schema = TypeSchema.describe(BatchRequest.class);
        @SuppressWarnings("unchecked") Map<String, Object> properties =
                (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked") Map<String, Object> addresses =
                (Map<String, Object>) properties.get("addresses");
        assertThat(addresses.get("type")).isEqualTo("array");
        @SuppressWarnings("unchecked") Map<String, Object> item =
                (Map<String, Object>) addresses.get("items");
        @SuppressWarnings("unchecked") Map<String, Object> itemProperties =
                (Map<String, Object>) item.get("properties");
        assertThat(itemProperties.keySet()).contains("city", "zip");
    }
}
