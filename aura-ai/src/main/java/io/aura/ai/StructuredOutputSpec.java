package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;

import java.util.Map;

/** Provider-neutral JSON Schema contract for one structured model response. */
public final class StructuredOutputSpec {
    private static final java.util.Set<String> SUPPORTED_KEYWORDS = java.util.Set.of(
            "$ref", "$defs", "definitions", "type", "title", "description",
            "properties", "required", "additionalProperties", "items", "prefixItems",
            "contains", "minContains", "maxContains", "enum", "const", "anyOf", "oneOf",
            "allOf", "pattern", "format", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "minItems",
            "maxItems", "uniqueItems", "minProperties", "maxProperties");
    private final String name;
    private final String schemaJson;
    private final boolean strict;
    private final JSONSchema validator;

    public StructuredOutputSpec(String name, Map<String, ?> schema, boolean strict) {
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("structured output name must be a stable identifier");
        }
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("structured output schema must not be empty");
        }
        this.name = name;
        JSONObject schemaObject;
        try {
            schemaObject = JSON.parseObject(JSON.toJSONString(schema));
            validateSchemaShape(schemaObject, schemaObject, "$");
            rejectRecursiveReferences(schemaObject);
            this.validator = JSONSchema.of(schemaObject);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "invalid structured output schema: " + error.getMessage(), error);
        }
        this.schemaJson = schemaObject.toJSONString();
        this.strict = strict;
        if (strict) {
            if (!"object".equals(schemaObject.get("type"))) {
                throw new IllegalArgumentException("strict structured output root must be an object");
            }
            validateStrictSchema(schemaObject, "$");
        }
    }

    public static StructuredOutputSpec strict(String name, Map<String, ?> schema) {
        return new StructuredOutputSpec(name, schema, true);
    }

    public String name() { return name; }
    @SuppressWarnings("unchecked")
    public Map<String, Object> schema() {
        return java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(JSON.parseObject(schemaJson, Map.class)));
    }
    public boolean strict() { return strict; }

    public void validate(String json) {
        if (json == null || json.isBlank()) {
            throw new AiStructuredOutputException("AI structured output is blank");
        }
        try {
            StrictJson.validate(json);
            Object value = JSON.parse(json);
            var result = validator.validate(value);
            if (!result.isSuccess()) {
                throw new AiStructuredOutputException("AI structured output does not match the requested schema");
            }
        } catch (AiStructuredOutputException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AiStructuredOutputException("AI structured output is not valid JSON", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateStrictSchema(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> schema)) return;
        Object type = schema.get("type");
        boolean objectType = "object".equals(type)
                || type instanceof java.util.Collection<?> types && types.contains("object");
        boolean objectKeywords = java.util.List.of(
                "properties", "required", "additionalProperties", "minProperties", "maxProperties")
                .stream().anyMatch(schema::containsKey);
        if (objectKeywords && !objectType) {
            throw new IllegalArgumentException(
                    "strict object keywords require type=object at " + path);
        }
        if (objectType) {
            if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                throw new IllegalArgumentException(
                        "strict object schema requires additionalProperties=false at " + path);
            }
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            if (!(schema.get("required") instanceof java.util.Collection<?> values)) {
                throw new IllegalArgumentException(
                        "strict object schema requires a required array at " + path);
            }
            java.util.Set<String> required = new java.util.LinkedHashSet<>();
            values.forEach(value -> required.add((String) value));
            if (!required.equals(properties.keySet())) {
                throw new IllegalArgumentException(
                        "strict object schema requires every property exactly once at " + path);
            }
            properties.forEach((name, nested) -> validateStrictSchema(nested, path + "." + name));
        }
        if (schema.get("items") != null) validateStrictSchema(schema.get("items"), path + "[]");
        for (String definitionKey : java.util.List.of("$defs", "definitions")) {
            if (schema.get(definitionKey) instanceof Map<?, ?> definitions) {
                definitions.forEach((name, nested) ->
                        validateStrictSchema(nested, path + "." + definitionKey + "." + name));
            }
        }
        for (String combinator : java.util.List.of("anyOf", "oneOf", "allOf")) {
            if (schema.get(combinator) instanceof java.util.Collection<?> alternatives) {
                int index = 0;
                for (Object alternative : alternatives) {
                    validateStrictSchema(alternative, path + "." + combinator + "[" + index++ + "]");
                }
            }
        }
    }

    private static void validateSchemaShape(Object raw, Map<?, ?> root, String path) {
        if (!(raw instanceof Map<?, ?> schema)) {
            throw new IllegalArgumentException("schema node must be an object at " + path);
        }
        for (Object rawKeyword : schema.keySet()) {
            if (!(rawKeyword instanceof String keyword) || !SUPPORTED_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException("unsupported schema keyword " + rawKeyword + " at " + path);
            }
        }
        Object type = schema.get("type");
        if (type != null) validateTypes(type, path + ".type");
        validateStringKeyword(schema, "$ref", path);
        validateStringKeyword(schema, "title", path);
        validateStringKeyword(schema, "description", path);
        validateStringKeyword(schema, "pattern", path);
        validateStringKeyword(schema, "format", path);
        if (schema.get("$ref") instanceof String reference
                && !reference.startsWith("#/$defs/") && !reference.startsWith("#/definitions/")) {
            throw new IllegalArgumentException("only local schema references are supported at " + path);
        }
        if (schema.get("$ref") instanceof String reference) {
            validateLocalReference(root, reference, path);
        }

        Object properties = schema.get("properties");
        if (properties != null) {
            if (!(properties instanceof Map<?, ?> propertyMap)) {
                throw new IllegalArgumentException("properties must be an object at " + path);
            }
            propertyMap.forEach((name, nested) -> {
                if (!(name instanceof String)) {
                    throw new IllegalArgumentException("property names must be strings at " + path);
                }
                validateSchemaShape(nested, root, path + ".properties." + name);
            });
        }

        Object required = schema.get("required");
        if (required != null) validateRequired(required, path);
        Object additional = schema.get("additionalProperties");
        if (additional != null && !(additional instanceof Boolean) && !(additional instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "additionalProperties must be boolean or schema at " + path);
        }
        if (additional instanceof Map<?, ?>) {
            validateSchemaShape(additional, root, path + ".additionalProperties");
        }

        Object items = schema.get("items");
        if (items != null) validateSchemaShape(items, root, path + ".items");
        Object prefixItems = schema.get("prefixItems");
        if (prefixItems != null) {
            if (!(prefixItems instanceof java.util.Collection<?> schemas)) {
                throw new IllegalArgumentException("prefixItems must be an array at " + path);
            }
            int index = 0;
            for (Object nested : schemas) {
                validateSchemaShape(nested, root, path + ".prefixItems[" + index++ + "]");
            }
        }
        Object contains = schema.get("contains");
        if (contains != null) validateSchemaShape(contains, root, path + ".contains");
        Object enumValues = schema.get("enum");
        if (enumValues != null && !(enumValues instanceof java.util.Collection<?>)) {
            throw new IllegalArgumentException("enum must be an array at " + path);
        }

        for (String keyword : java.util.List.of(
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")) {
            Object value = schema.get(keyword);
            if (value != null && !(value instanceof Number)) {
                throw new IllegalArgumentException(keyword + " must be numeric at " + path);
            }
        }
        if (schema.get("multipleOf") instanceof Number multipleOf
                && multipleOf.doubleValue() <= 0) {
            throw new IllegalArgumentException("multipleOf must be positive at " + path);
        }
        for (String keyword : java.util.List.of(
                "minLength", "maxLength", "minItems", "maxItems", "minProperties",
                "maxProperties", "minContains", "maxContains")) {
            Object value = schema.get(keyword);
            if (value != null && (!(value instanceof Number number)
                    || number.longValue() < 0 || number.doubleValue() != number.longValue())) {
                throw new IllegalArgumentException(keyword + " must be a non-negative integer at " + path);
            }
        }
        if (schema.get("uniqueItems") != null && !(schema.get("uniqueItems") instanceof Boolean)) {
            throw new IllegalArgumentException("uniqueItems must be boolean at " + path);
        }

        for (String definitionKey : java.util.List.of("$defs", "definitions")) {
            Object definitions = schema.get(definitionKey);
            if (definitions != null) {
                if (!(definitions instanceof Map<?, ?> definitionMap)) {
                    throw new IllegalArgumentException(definitionKey + " must be an object at " + path);
                }
                definitionMap.forEach((name, nested) ->
                        validateSchemaShape(nested, root, path + "." + definitionKey + "." + name));
            }
        }
        for (String combinator : java.util.List.of("anyOf", "oneOf", "allOf")) {
            Object alternatives = schema.get(combinator);
            if (alternatives != null) {
                if (!(alternatives instanceof java.util.Collection<?> collection) || collection.isEmpty()) {
                    throw new IllegalArgumentException(combinator + " must be a non-empty array at " + path);
                }
                int index = 0;
                for (Object alternative : collection) {
                    validateSchemaShape(
                            alternative, root, path + "." + combinator + "[" + index++ + "]");
                }
            }
        }
    }

    private static Map<?, ?> validateLocalReference(Map<?, ?> root, String reference, String path) {
        Object target = root;
        String[] tokens = reference.substring(2).split("/", -1);
        for (String rawToken : tokens) {
            String token = decodePointerToken(rawToken, path);
            if (target instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw new IllegalArgumentException(
                            "local schema reference target does not exist at " + path);
                }
                target = map.get(token);
            } else if (target instanceof java.util.List<?> list) {
                if (!token.matches("0|[1-9][0-9]*")) {
                    throw new IllegalArgumentException("invalid local schema array reference at " + path);
                }
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("invalid local schema array reference at " + path, error);
                }
                if (index >= list.size()) {
                    throw new IllegalArgumentException(
                            "local schema reference target does not exist at " + path);
                }
                target = list.get(index);
            } else {
                throw new IllegalArgumentException(
                        "local schema reference target does not exist at " + path);
            }
        }
        if (!(target instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("local schema reference must target a schema at " + path);
        }
        return (Map<?, ?>) target;
    }

    private static void rejectRecursiveReferences(Map<?, ?> root) {
        visitSchema(root, root, new java.util.IdentityHashMap<>());
    }

    private static void visitSchema(
            Map<?, ?> schema, Map<?, ?> root, java.util.IdentityHashMap<Map<?, ?>, Integer> states) {
        Integer state = states.get(schema);
        if (state != null) {
            if (state == 1) {
                throw new IllegalArgumentException(
                        "recursive local schema references are not supported by the local validator");
            }
            return;
        }
        states.put(schema, 1);
        if (schema.get("$ref") instanceof String reference) {
            visitSchema(validateLocalReference(root, reference, "$ref"), root, states);
        }
        for (Map<?, ?> child : schemaChildren(schema)) {
            visitSchema(child, root, states);
        }
        states.put(schema, 2);
    }

    private static java.util.List<Map<?, ?>> schemaChildren(Map<?, ?> schema) {
        java.util.List<Map<?, ?>> children = new java.util.ArrayList<>();
        addMapValues(children, schema.get("properties"));
        addMapValues(children, schema.get("$defs"));
        addMapValues(children, schema.get("definitions"));
        addSchema(children, schema.get("items"));
        addSchema(children, schema.get("additionalProperties"));
        addSchema(children, schema.get("contains"));
        addSchemaList(children, schema.get("prefixItems"));
        addSchemaList(children, schema.get("anyOf"));
        addSchemaList(children, schema.get("oneOf"));
        addSchemaList(children, schema.get("allOf"));
        return children;
    }

    private static void addMapValues(java.util.List<Map<?, ?>> children, Object value) {
        if (value instanceof Map<?, ?> map) map.values().forEach(child -> addSchema(children, child));
    }

    private static void addSchemaList(java.util.List<Map<?, ?>> children, Object value) {
        if (value instanceof java.util.Collection<?> collection) {
            collection.forEach(child -> addSchema(children, child));
        }
    }

    private static void addSchema(java.util.List<Map<?, ?>> children, Object value) {
        if (value instanceof Map<?, ?> child) children.add(child);
    }

    private static String decodePointerToken(String token, String path) {
        StringBuilder decoded = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            if (current != '~') {
                decoded.append(current);
                continue;
            }
            if (++index >= token.length()) {
                throw new IllegalArgumentException("invalid JSON Pointer escape at " + path);
            }
            char escaped = token.charAt(index);
            if (escaped == '0') decoded.append('~');
            else if (escaped == '1') decoded.append('/');
            else throw new IllegalArgumentException("invalid JSON Pointer escape at " + path);
        }
        return decoded.toString();
    }

    private static void validateRequired(Object value, String path) {
        if (!(value instanceof java.util.Collection<?> required)) {
            throw new IllegalArgumentException("required must be an array at " + path);
        }
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        for (Object item : required) {
            if (!(item instanceof String name) || !unique.add(name)) {
                throw new IllegalArgumentException(
                        "required must contain unique string property names at " + path);
            }
        }
    }

    private static void validateTypes(Object value, String path) {
        java.util.Set<String> allowed = java.util.Set.of(
                "object", "array", "string", "number", "integer", "boolean", "null");
        if (value instanceof String type) {
            if (!allowed.contains(type)) throw new IllegalArgumentException("unsupported type at " + path);
            return;
        }
        if (!(value instanceof java.util.Collection<?> types) || types.isEmpty()) {
            throw new IllegalArgumentException("type must be a string or non-empty string array at " + path);
        }
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        for (Object rawType : types) {
            if (!(rawType instanceof String type) || !allowed.contains(type) || !unique.add(type)) {
                throw new IllegalArgumentException("type array must contain unique JSON types at " + path);
            }
        }
    }

    private static void validateStringKeyword(Map<?, ?> schema, String keyword, String path) {
        if (schema.get(keyword) != null && !(schema.get(keyword) instanceof String)) {
            throw new IllegalArgumentException(keyword + " must be a string at " + path);
        }
    }
}
