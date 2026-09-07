package io.aura.web;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a small, deterministic JSON schema for request body types. */
final class TypeSchema {
    private static final int MAX_DEPTH = 5;

    private TypeSchema() { }

    static Map<String, Object> describe(Class<?> type) {
        return describe((Type) type, new HashSet<>(), 0);
    }

    static Map<String, Object> describe(Type type) {
        return describe(type, new HashSet<>(), 0);
    }

    private static Map<String, Object> describe(Type genericType, Set<Class<?>> visiting, int depth) {
        if (genericType == null) return object();
        if (genericType instanceof GenericArrayType array) {
            Map<String, Object> schema = type("array");
            schema.put("items", describe(array.getGenericComponentType(), visiting, depth + 1));
            return schema;
        }
        if (genericType instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (!(raw instanceof Class<?> rawClass)) return object();
            if (Map.class.isAssignableFrom(rawClass)) return object();
            if (Iterable.class.isAssignableFrom(rawClass)) {
                Map<String, Object> schema = type("array");
                Type[] arguments = parameterized.getActualTypeArguments();
                schema.put("items", arguments.length == 0 ? object()
                        : describe(arguments[0], visiting, depth + 1));
                return schema;
            }
            return describe(rawClass, visiting, depth);
        }
        if (!(genericType instanceof Class<?> type)) return object();
        if (type == String.class || type == Character.class || type == char.class) return type("string");
        if (type == boolean.class || type == Boolean.class) return type("boolean");
        if (type == byte.class || type == short.class || type == int.class || type == long.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class)
            return type("integer");
        if (type == float.class || type == double.class || type == Float.class || type == Double.class
                || Number.class.isAssignableFrom(type)) return type("number");
        if (type.isEnum()) {
            Map<String, Object> schema = type("string");
            List<String> values = new ArrayList<>();
            for (Object value : type.getEnumConstants()) values.add(((Enum<?>) value).name());
            schema.put("enum", values);
            return schema;
        }
        if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
            Map<String, Object> schema = type("array");
            schema.put("items", type.isArray()
                    ? describe(type.getComponentType(), visiting, depth + 1) : object());
            return schema;
        }
        if (Map.class.isAssignableFrom(type)) return object();
        if (depth >= MAX_DEPTH || !visiting.add(type)) return object();
        try {
            Map<String, Object> schema = object();
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    properties.put(component.getName(), describe(component.getGenericType(), visiting, depth + 1));
                    // Records have no field defaults; every component is part of the input contract.
                    required.add(component.getName());
                }
            } else {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    properties.put(field.getName(), describe(field.getGenericType(), visiting, depth + 1));
                }
            }
            schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
            return schema;
        } finally {
            visiting.remove(type);
        }
    }

    private static Map<String, Object> object() { return type("object"); }

    private static Map<String, Object> type(String value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", value);
        return schema;
    }
}
