package io.aura.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates invocation arguments against the declared parameter schemas, at the boundary every
 * transport shares: after identity and authorization, before the handler runs.
 *
 * <p>{@link ApiParameter#schema()} is the authority for the constraint keywords, and
 * {@link ApiParameter#type()} names the JSON type a schema-less scalar advertises — the same
 * {@link ScalarType#jsonTypeName} table the schema funnels render with. A scalar argument
 * arrives as JSON or as the string encoding a client chose, and both bind the way the typed
 * readers do: {@code "5"} is a valid integer and {@code "5.0"} is not, because the binder would
 * refuse it too. The validator never converts what passes — it only refuses what could not be
 * read as advertised.
 *
 * <p>The implemented constraint set is {@code type}, {@code enum}, {@code minimum},
 * {@code maximum}, {@code properties} and {@code required}. Other keywords are ignored, as JSON
 * Schema defines: a keyword a validator does not implement is not violated. Numeric comparisons
 * are by value — {@code 1} and {@code 1.0} are equal — never by Java type.
 *
 * <p>Absent arguments are not this class's business: presence is the required check the
 * transport already runs, so {@code null} values are skipped here.
 */
public final class ApiSchemaValidation {

    private ApiSchemaValidation() { }

    /**
     * Validates every declared parameter present in {@code args}. A violation throws
     * {@link ContractException} with code {@code AURA_PARAM_SCHEMA_VIOLATION}, naming the
     * parameter path, the violated constraint, and the value that violated it — so the caller
     * can surface the failure the same way it surfaces every other boundary refusal.
     */
    public static void validate(List<ApiParameter> declared, Map<String, Object> args) {
        if (declared == null) return;
        for (ApiParameter param : declared) {
            Object value = args == null ? null : args.get(param.name());
            if (value == null) continue;
            check(param.name(), param.schema(), typeOf(param), value);
        }
    }

    private static void check(String path, Map<String, Object> schema, String type, Object value) {
        if (type != null) checkType(path, type, value);
        if (schema == null || schema.isEmpty()) return;
        checkEnum(path, schema.get("enum"), value);
        checkBounds(path, schema, value);
        if (value instanceof Map<?, ?> nested) checkNested(path, schema, nested);
    }

    private static void checkType(String path, String type, Object value) {
        switch (type) {
            case "integer" -> {
                if (!(value instanceof Number number && isIntegral(number)) && !isIntegerText(value)) {
                    throw violation(path, "expected an integer", value);
                }
            }
            case "number" -> {
                if (decimal(value) == null) throw violation(path, "expected a number", value);
            }
            case "string" -> {
                if (!(value instanceof String)) throw violation(path, "expected a string", value);
            }
            case "boolean" -> {
                if (!(value instanceof Boolean) && !isBooleanText(value)) {
                    throw violation(path, "expected a boolean", value);
                }
            }
            case "object" -> {
                if (!(value instanceof Map)) throw violation(path, "expected an object", value);
            }
            case "array" -> {
                if (!(value instanceof List)) throw violation(path, "expected an array", value);
            }
            default -> { /* a type this validator does not implement is not violated */ }
        }
    }

    private static void checkEnum(String path, Object declared, Object value) {
        if (!(declared instanceof List<?> allowed) || allowed.isEmpty()) return;
        for (Object candidate : allowed) {
            if (equalByValue(candidate, value)) return;
        }
        throw violation(path, "not one of the declared enum " + allowed, value);
    }

    private static void checkBounds(String path, Map<String, Object> schema, Object value) {
        BigDecimal number = decimal(value);
        if (number == null) return;
        if (schema.get("minimum") instanceof Number minimum
                && number.compareTo(new BigDecimal(minimum.toString())) < 0) {
            throw violation(path, "below the declared minimum " + minimum, value);
        }
        if (schema.get("maximum") instanceof Number maximum
                && number.compareTo(new BigDecimal(maximum.toString())) > 0) {
            throw violation(path, "above the declared maximum " + maximum, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkNested(String path, Map<String, Object> schema, Map<?, ?> value) {
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (name instanceof String key && !value.containsKey(key)) {
                    throw violation(path + "." + key, "required by the declared schema", "absent");
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                if (!(property.getKey() instanceof String key)
                        || !(property.getValue() instanceof Map<?, ?> nestedSchema)
                        || value.get(key) == null) continue;
                String nestedType = nestedSchema.get("type") instanceof String type ? type : null;
                check(path + "." + key, (Map<String, Object>) nestedSchema, nestedType, value.get(key));
            }
        }
    }

    /** The declared JSON type: the schema's own {@code type} key, or the Java type's rendering. */
    private static String typeOf(ApiParameter param) {
        Map<String, Object> schema = param.schema();
        if (schema.get("type") instanceof String declared) return declared;
        String jsonType = ScalarType.jsonTypeName(param.type());
        return jsonType != null ? jsonType : wireName(param.type());
    }

    /** A declared type may already be the JSON wire name — the schema funnels accept those too. */
    private static String wireName(String javaTypeName) {
        return switch (javaTypeName == null ? "" : javaTypeName.trim()) {
            case "integer", "number", "string", "boolean", "object", "array" -> javaTypeName.trim();
            default -> null;
        };
    }

    private static boolean isIntegral(Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof Long || number instanceof java.math.BigInteger) {
            return true;
        }
        BigDecimal decimal = decimal(number);
        return decimal != null && decimal.stripTrailingZeros().scale() <= 0;
    }

    /** String-encoded integers bind the way the binder binds them: {@code Long.parseLong}. */
    private static boolean isIntegerText(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            Long.parseLong(text);
            return true;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static boolean isBooleanText(Object value) {
        return value instanceof String text
                && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text));
    }

    /** The numeric interpretation of a value, or null when there is none. */
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal exact) return exact;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof Float || value instanceof Double) {
            double doubleValue = ((Number) value).doubleValue();
            return Double.isFinite(doubleValue) ? new BigDecimal(Double.toString(doubleValue)) : null;
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }

    private static boolean equalByValue(Object declared, Object actual) {
        if (declared instanceof Number && actual instanceof Number) {
            BigDecimal left = decimal(declared);
            BigDecimal right = decimal(actual);
            return left != null && right != null && left.compareTo(right) == 0;
        }
        return Objects.equals(declared, actual);
    }

    private static ContractException violation(String path, String constraint, Object value) {
        return new ContractException("AURA_PARAM_SCHEMA_VIOLATION",
                "Parameter '" + path + "' violates its declared schema: " + constraint
                        + " (found " + value + ")");
    }
}
