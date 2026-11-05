package io.aura;

import java.util.Map;

class ConfigBinder {

    @SuppressWarnings("unchecked")
    static <T> T bind(Map<String, String> props, String prefix, Class<T> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException("props(prefix, Class) only supports Record types");
        }
        var components = recordType.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var rc = components[i];
            paramTypes[i] = rc.getType();
            String key = prefix + rc.getName();
            args[i] = convertValue(key, props.get(key), rc.getType());
        }
        try {
            return (T) recordType.getDeclaredConstructor(paramTypes).newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to bind config to " + recordType.getSimpleName(), e);
        }
    }

    static Object convertValue(String value, Class<?> type) {
        return convertValue(null, value, type);
    }

    static Object convertValue(String key, String value, Class<?> type) {
        if (type == String.class) return value;
        if (value == null || value.isBlank()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == boolean.class) return false;
            return null;
        }
        try {
            if (type == int.class || type == Integer.class) return Integer.parseInt(value);
            if (type == long.class || type == Long.class) return Long.parseLong(value);
            if (type == double.class || type == Double.class) return Double.parseDouble(value);
            if (type == boolean.class || type == Boolean.class) return parseBoolean(key, value);
        } catch (NumberFormatException invalid) {
            throw invalidValue(key, value, type, invalid);
        }
        return value;
    }

    private static boolean parseBoolean(String key, String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw invalidValue(key, value, boolean.class, null);
    }

    /**
     * The key is the only part of a binding failure an operator can act on, so it leads the
     * message and the code stays stable for anything that logs or alerts on it. A bare
     * {@code NumberFormatException} says what went wrong to a Java programmer and nothing to
     * whoever typed the property.
     */
    private static IllegalArgumentException invalidValue(String key, String value,
                                                        Class<?> type, Exception cause) {
        String name = key == null ? "configuration value" : key;
        return new IllegalArgumentException("AURA_CONFIG_INVALID: " + name + " expects "
                + typeName(type) + " but was '" + value + "'", cause);
    }

    private static String typeName(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return "a boolean (true or false)";
        if (type == int.class || type == Integer.class) return "an integer";
        if (type == long.class || type == Long.class) return "a long integer";
        if (type == double.class || type == Double.class) return "a decimal number";
        return type.getSimpleName();
    }
}
