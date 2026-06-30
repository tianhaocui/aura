package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;

import java.lang.reflect.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public final class OpenApiGenerator {

    public static String generate(List<CompiledRoute> routes, Aura app) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        String title = app.openapiTitle() != null ? app.openapiTitle()
                : app.prop("app.name") != null ? app.prop("app.name") : "Aura App";
        info.put("title", title);
        info.put("version", "1.0.0");
        doc.put("info", info);

        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();

        for (CompiledRoute cr : routes) {
            String path = cr.rawPath();
            if ("/__schema__".equals(path) || "/openapi.json".equals(path)) continue;

            Map<String, Object> operation = buildOperation(cr, schemas, app);
            paths.computeIfAbsent(path, k -> new LinkedHashMap<>())
                    .put(cr.method().toLowerCase(), operation);
        }

        doc.put("paths", paths);
        if (!schemas.isEmpty()) {
            doc.put("components", Map.of("schemas", schemas));
        }
        return JSON.toJSONString(doc);
    }

    private static Map<String, Object> buildOperation(CompiledRoute cr, Map<String, Map<String, Object>> schemas, Aura app) {
        Map<String, Object> op = new LinkedHashMap<>();

        if (cr.meta() != null && cr.meta().description() != null) {
            op.put("description", cr.meta().description());
        }

        if (!(cr.handler() instanceof MethodRefHandler mh)) {
            if (!cr.paramNames().isEmpty()) {
                List<Map<String, Object>> params = new ArrayList<>();
                for (String name : cr.paramNames()) {
                    Map<String, Object> param = new LinkedHashMap<>();
                    param.put("name", name);
                    param.put("in", "path");
                    param.put("required", true);
                    param.put("schema", Map.of("type", "string"));
                    params.add(param);
                }
                op.put("parameters", params);
            }
            op.put("responses", Map.of("200", Map.of("description", "OK")));
            return op;
        }

        Method method = mh.resolvedMethod();
        op.put("operationId", method.getDeclaringClass().getSimpleName() + "." + method.getName());

        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            if (p.getType() == Context.class || p.getType() == BaseContext.class) continue;
            if (app.paramResolvers().containsKey(p.getType())) continue;

            if (p.getType().isRecord() || TypeUtil.isPojo(p.getType())) {
                Map<String, Object> schema = buildSchema(p.getType(), schemas);
                Map<String, Object> content = Map.of("application/json", Map.of("schema", schema));
                op.put("requestBody", Map.of("required", true, "content", content));
            } else if (cr.paramNames().contains(p.getName())) {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.getName());
                param.put("in", "path");
                param.put("required", true);
                param.put("schema", typeSchema(p.getType(), null, schemas));
                parameters.add(param);
            } else {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.getName());
                param.put("in", "query");
                param.put("schema", typeSchema(p.getType(), null, schemas));
                parameters.add(param);
            }
        }
        if (!parameters.isEmpty()) op.put("parameters", parameters);

        Map<String, Object> responses = new LinkedHashMap<>();
        Class<?> returnType = method.getReturnType();
        if (returnType != void.class && returnType != Void.class) {
            Map<String, Object> schema = typeSchema(returnType, method.getGenericReturnType(), schemas);
            responses.put("200", Map.of("description", "OK",
                    "content", Map.of("application/json", Map.of("schema", schema))));
        } else {
            responses.put("200", Map.of("description", "OK"));
        }
        op.put("responses", responses);

        return op;
    }

    private static Map<String, Object> buildSchema(Class<?> type, Map<String, Map<String, Object>> schemas) {
        String name = type.getSimpleName();
        if (schemas.containsKey(name)) return Map.of("$ref", "#/components/schemas/" + name);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schemas.put(name, schema);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                Map<String, Object> prop = typeSchema(rc.getType(), rc.getGenericType(), schemas);
                applyConstraints(rc, prop, required, rc.getName());
                properties.put(rc.getName(), prop);
            }
        } else {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Map<String, Object> prop = typeSchema(f.getType(), f.getGenericType(), schemas);
                applyFieldConstraints(f, prop, required);
                properties.put(f.getName(), prop);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return Map.of("$ref", "#/components/schemas/" + name);
    }

    private static void applyConstraints(AnnotatedElement el, Map<String, Object> prop, List<String> required, String name) {
        for (var a : el.getAnnotations()) {
            String aName = a.annotationType().getSimpleName();
            switch (aName) {
                case "NotNull", "NotBlank" -> required.add(name);
                case "Min" -> { try { prop.put("minimum", a.annotationType().getMethod("value").invoke(a)); } catch (Exception ignored) {} }
                case "Max" -> { try { prop.put("maximum", a.annotationType().getMethod("value").invoke(a)); } catch (Exception ignored) {} }
                case "Size" -> {
                    try {
                        int min = (int) a.annotationType().getMethod("min").invoke(a);
                        int max = (int) a.annotationType().getMethod("max").invoke(a);
                        if (min > 0) prop.put("minLength", min);
                        if (max < Integer.MAX_VALUE) prop.put("maxLength", max);
                    } catch (Exception ignored) {}
                }
                case "Pattern" -> { try { prop.put("pattern", a.annotationType().getMethod("value").invoke(a)); } catch (Exception ignored) {} }
            }
        }
    }

    private static void applyFieldConstraints(Field f, Map<String, Object> prop, List<String> required) {
        applyConstraints(f, prop, required, f.getName());
    }

    private static Map<String, Object> typeSchema(Class<?> type, Type generic, Map<String, Map<String, Object>> schemas) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (type == String.class) { s.put("type", "string"); }
        else if (type == int.class || type == Integer.class) { s.put("type", "integer"); s.put("format", "int32"); }
        else if (type == long.class || type == Long.class) { s.put("type", "integer"); s.put("format", "int64"); }
        else if (type == double.class || type == Double.class) { s.put("type", "number"); s.put("format", "double"); }
        else if (type == boolean.class || type == Boolean.class) { s.put("type", "boolean"); }
        else if (type == LocalDate.class) { s.put("type", "string"); s.put("format", "date"); }
        else if (type == LocalDateTime.class) { s.put("type", "string"); s.put("format", "date-time"); }
        else if (List.class.isAssignableFrom(type)) {
            s.put("type", "array");
            if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) {
                    s.put("items", typeSchema(c, null, schemas));
                } else {
                    s.put("items", Map.of("type", "object"));
                }
            } else {
                s.put("items", Map.of("type", "object"));
            }
        } else if (type.isRecord() || TypeUtil.isPojo(type)) {
            return buildSchema(type, schemas);
        } else {
            s.put("type", "string");
        }
        return s;
    }

    private OpenApiGenerator() {}
}