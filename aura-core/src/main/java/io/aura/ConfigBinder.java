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
            String value = props.get(prefix + rc.getName());
            args[i] = convertValue(value, rc.getType());
        }
        try {
            return (T) recordType.getDeclaredConstructor(paramTypes).newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to bind config to " + recordType.getSimpleName(), e);
        }
    }

    static Object convertValue(String value, Class<?> type) {
        if (type == String.class) return value;
        if (value == null || value.isBlank()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == boolean.class || type == Boolean.class) return "true".equalsIgnoreCase(value);
        return value;
    }
}
