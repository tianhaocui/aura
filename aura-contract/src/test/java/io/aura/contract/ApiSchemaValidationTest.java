package io.aura.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The declared schema is the contract the handler's arguments are held to. A scalar arrives as
 * JSON or as the string a client encoded, and both bind the way the typed readers do — the
 * validator refuses what could not be read as advertised and lets everything else through
 * untouched.
 */
class ApiSchemaValidationTest {

    private static ApiParameter param(String type, Map<String, Object> schema) {
        return new ApiParameter("value", type, "query", true, null, schema);
    }

    private static void assertValid(ApiParameter declared, Object value) {
        assertThatCode(() -> ApiSchemaValidation.validate(List.of(declared), Map.of("value", value)))
                .doesNotThrowAnyException();
    }

    private static void assertViolation(ApiParameter declared, Object value, String... messageParts) {
        assertThatThrownBy(() -> ApiSchemaValidation.validate(List.of(declared), Map.of("value", value)))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_PARAM_SCHEMA_VIOLATION");
                    assertThat(e.error().message()).contains("value");
                    for (String part : messageParts) {
                        assertThat(e.error().message()).contains(part);
                    }
                });
    }

    @Test
    void anIntegerAcceptsJsonNumbersIntegralDoublesAndBinderParseableStrings() {
        assertValid(param("long", Map.of()), 42);
        assertValid(param("long", Map.of()), 5L);
        assertValid(param("long", Map.of()), 5.0);
        assertValid(param("long", Map.of()), "5");
        assertValid(param("integer", Map.of("type", "integer")), 3);

        assertViolation(param("long", Map.of()), "5.0", "expected an integer");
        assertViolation(param("long", Map.of()), "not-a-number", "expected an integer", "not-a-number");
        assertViolation(param("long", Map.of()), 5.5, "expected an integer");
    }

    @Test
    void aNumberAcceptsIntegersDecimalsAndTheirStringEncodings() {
        assertValid(param("double", Map.of()), 3);
        assertValid(param("double", Map.of()), 2.5);
        assertValid(param("double", Map.of()), "2.5");

        assertViolation(param("double", Map.of()), "abc", "expected a number");
    }

    @Test
    void aStringRefusesEveryOtherShape() {
        assertValid(param("String", Map.of()), "NEW");
        assertViolation(param("String", Map.of()), 5, "expected a string");
    }

    @Test
    void aBooleanAcceptsJsonBooleansAndTrueFalseEncodingsOnly() {
        assertValid(param("boolean", Map.of()), true);
        assertValid(param("boolean", Map.of()), "false");

        assertViolation(param("boolean", Map.of()), "banana", "expected a boolean");
        assertViolation(param("boolean", Map.of()), 1, "expected a boolean");
    }

    @Test
    void declaredObjectAndArrayShapesAreHeld() {
        ApiParameter body = param("CreateOrderRequest", Map.of("type", "object"));
        assertValid(body, Map.of("item", "widget"));
        assertViolation(body, List.of("item"), "expected an object");

        ApiParameter tags = param("tags", Map.of("type", "array"));
        assertValid(tags, List.of("a", "b"));
        assertViolation(tags, "a", "expected an array");
    }

    @Test
    void aWireNameTypeDeclaresItselfWithoutASchema() {
        assertValid(param("integer", Map.of()), 7);
        assertViolation(param("integer", Map.of()), "seven", "expected an integer");
    }

    @Test
    void enumComparesByValueAndNamesTheRefusedValue() {
        ApiParameter status = param("OrderStatus",
                Map.of("type", "string", "enum", List.of("NEW", "PROCESSING", "DONE", "CANCEL")));

        assertValid(status, "PROCESSING");
        assertViolation(status, "INVALID", "not one of the declared enum", "INVALID");
    }

    @Test
    void boundsCompareByValueSoOneAndOnePointZeroAreTheSame() {
        ApiParameter id = param("long", Map.of("type", "integer", "minimum", 1, "maximum", 100));

        assertValid(id, 1);
        assertValid(id, 1.0);
        assertValid(id, "100");
        assertViolation(id, -999, "below the declared minimum", "-999");
        assertViolation(id, 101, "above the declared maximum");
    }

    @Test
    void aNestedObjectHoldsItsOwnRequiredAndConstraints() {
        Map<String, Object> line = Map.of(
                "type", "object",
                "properties", Map.of(
                        "quantity", Map.of("type", "integer", "minimum", 1),
                        "item", Map.of("type", "string")),
                "required", List.of("item"));
        ApiParameter order = param("CreateOrderRequest", line);

        assertValid(order, Map.of("item", "widget", "quantity", 2));
        assertValid(order, Map.of("item", "widget"));

        assertViolation(order, Map.of("quantity", 2), "value.item", "required by the declared schema");
        assertViolation(order, Map.of("item", "widget", "quantity", 0),
                "value.quantity", "below the declared minimum");
        assertViolation(order, Map.of("item", 5), "value.item", "expected a string");
    }

    @Test
    void aConstraintThisValidatorDoesNotImplementIsNotViolated() {
        assertValid(param("String", Map.of("type", "string", "pattern", "^A")),
                "anything");

        ApiParameter body = param("CreateOrderRequest",
                Map.of("type", "object", "minProperties", 2, "required", List.of("item")));
        assertValid(body, Map.of("item", "widget"));
    }

    @Test
    void absentArgumentsAreTheRequiredChecksBusiness() {
        assertThatCode(() -> ApiSchemaValidation.validate(
                List.of(param("long", Map.of("minimum", 1))), Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApiSchemaValidation.validate(null, Map.of("value", "anything")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApiSchemaValidation.validate(List.of(param("long", Map.of())), null))
                .doesNotThrowAnyException();
    }
}
