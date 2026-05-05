package io.aura.web;

public final class TypeUtil {

    public static boolean isPojo(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isEnum()) return false;
        return !type.getPackageName().startsWith("java.");
    }

    private TypeUtil() {}
}
