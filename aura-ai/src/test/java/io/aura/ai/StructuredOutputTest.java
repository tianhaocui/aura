package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.util.Map;

class StructuredOutputTest {
    record Answer(String answer, int score) { }

    @Test
    void parsesAndValidatesJsonResponse() {
        Answer answer = StructuredOutput.parseAndValidate(
                AiResponse.text("{\"answer\":\"ok\",\"score\":7}"), Answer.class,
                value -> { if (value.score() < 1) throw new IllegalArgumentException("score"); });
        assertThat(answer.answer()).isEqualTo("ok");
        assertThat(answer.score()).isEqualTo(7);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> StructuredOutput.parse("{bad", Answer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid structured output JSON");
        assertThatThrownBy(() -> StructuredOutput.validateJson("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaValidationRejectsMissingWrongAndAdditionalFields() {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of(
                        "answer", Map.of("type", "string"),
                        "score", Map.of("type", "integer")),
                "required", java.util.List.of("answer", "score"),
                "additionalProperties", false));

        spec.validate("{\"answer\":\"ok\",\"score\":7}");
        assertThatThrownBy(() -> spec.validate("{\"answer\":\"ok\"}"))
                .isInstanceOf(AiStructuredOutputException.class)
                .extracting("code").isEqualTo(AiStructuredOutputException.CODE);
        assertThatThrownBy(() -> spec.validate("{\"answer\":\"ok\",\"score\":\"high\"}"))
                .isInstanceOf(AiStructuredOutputException.class);
        assertThatThrownBy(() -> spec.validate(
                "{\"answer\":\"ok\",\"score\":7,\"extra\":true}"))
                .isInstanceOf(AiStructuredOutputException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAccessorCannotMutateTheValidationContract() {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));
        Map<String, Object> exposed = spec.schema();
        ((Map<String, Object>) exposed.get("properties")).clear();

        assertThatThrownBy(() -> spec.validate("{}"))
                .isInstanceOf(AiStructuredOutputException.class);
        assertThat(spec.schema()).extractingByKey("properties").isNotEqualTo(Map.of());
    }

    @Test
    void rejectsFastjsonExtensionsAndInvalidStrictSchemas() {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));

        assertThatThrownBy(() -> spec.validate("{'answer':'ok'}"))
                .isInstanceOf(AiStructuredOutputException.class);
        assertThatThrownBy(() -> spec.validate("{\"answer\":\"ok\",}"))
                .isInstanceOf(AiStructuredOutputException.class);
        assertThatThrownBy(() -> spec.validate("/*comment*/{\"answer\":\"ok\"}"))
                .isInstanceOf(AiStructuredOutputException.class);
        assertThatThrownBy(() -> StructuredOutputSpec.strict("invalid", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalProperties=false");
    }

    @Test
    void rejectsIncompleteNestedAndNullableStrictObjectSchemas() {
        assertThatThrownBy(() -> StructuredOutputSpec.strict("nested", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("$ref", "#/$defs/value")),
                "required", java.util.List.of("value"),
                "additionalProperties", false,
                "$defs", Map.of("value", Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", java.util.List.of("name"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalProperties=false");

        assertThatThrownBy(() -> StructuredOutputSpec.strict("nullable", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of(
                        "type", java.util.List.of("object", "null"),
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", java.util.List.of("name"))),
                "required", java.util.List.of("value"),
                "additionalProperties", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalProperties=false");
    }

    @Test
    void rejectsAmbiguousStrictSchemaDeclarations() {
        assertThatThrownBy(() -> StructuredOutputSpec.strict("scalar", Map.of("type", "string")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root must be an object");
        assertThatThrownBy(() -> StructuredOutputSpec.strict("duplicate", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("type", "string")),
                "required", java.util.List.of("value", "value"),
                "additionalProperties", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique string property names");
        assertThatThrownBy(() -> StructuredOutputSpec.strict("non_string", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("type", "string")),
                "required", java.util.List.of(1),
                "additionalProperties", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique string property names");
        assertThatThrownBy(() -> StructuredOutputSpec.strict("missing_required", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a required array");
    }

    @Test
    void validatesSchemaKeywordTypesAndLocalReferences() {
        assertThatThrownBy(() -> new StructuredOutputSpec("invalid", Map.of(
                "type", "object",
                "additionalProperties", "no"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid structured output schema");
        assertThatThrownBy(() -> new StructuredOutputSpec("external", Map.of(
                "$ref", "https://example.test/schema.json"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid structured output schema");

        StructuredOutputSpec spec = StructuredOutputSpec.strict("local_ref", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("$ref", "#/$defs/value")),
                "required", java.util.List.of("value"),
                "additionalProperties", false,
                "$defs", Map.of("value", Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", java.util.List.of("name"),
                        "additionalProperties", false))));

        spec.validate("{\"value\":{\"name\":\"ok\"}}");
        assertThatThrownBy(() -> spec.validate("{\"value\":{}}"))
                .isInstanceOf(AiStructuredOutputException.class);
    }

    @Test
    void rejectsMissingAndRecursiveLocalReferences() {
        assertThatThrownBy(() -> StructuredOutputSpec.strict("missing_ref", Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("$ref", "#/$defs/missing")),
                "required", java.util.List.of("value"),
                "additionalProperties", false,
                "$defs", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference target does not exist");

        assertThatThrownBy(() -> StructuredOutputSpec.strict("recursive", Map.of(
                "type", "object",
                "properties", Map.of("node", Map.of("$ref", "#/$defs/node")),
                "required", java.util.List.of("node"),
                "additionalProperties", false,
                "$defs", Map.of("node", Map.of(
                        "type", "object",
                        "properties", Map.of("next", Map.of("$ref", "#/$defs/node")),
                        "required", java.util.List.of("next"),
                        "additionalProperties", false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive local schema references are not supported");
    }

    @Test
    void rejectsImplicitStrictObjectsAndInvalidSupportedKeywords() {
        assertThatThrownBy(() -> StructuredOutputSpec.strict("implicit", Map.of(
                "type", "object",
                "properties", Map.of("child", Map.of(
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", java.util.List.of("name"),
                        "additionalProperties", false)),
                "required", java.util.List.of("child"),
                "additionalProperties", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object keywords require type=object");
        assertThatThrownBy(() -> new StructuredOutputSpec("multiple", Map.of(
                "type", "number", "multipleOf", "2"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multipleOf must be numeric");
        assertThatThrownBy(() -> new StructuredOutputSpec("properties", Map.of(
                "type", "object", "minProperties", "1"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minProperties must be a non-negative integer");
        assertThatThrownBy(() -> new StructuredOutputSpec("unknown", Map.of(
                "type", "string", "contentEncoding", "base64"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported schema keyword contentEncoding");
    }
}
