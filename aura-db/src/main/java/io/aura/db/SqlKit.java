package io.aura.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqlKit {

    private static final Pattern DIRECTIVE = Pattern.compile(
            "#(where|and|or|orderBy)\\(([^)]+)\\)");

    public record Parsed(String sql, Object[] params) {}

    public static Parsed parse(String template, Map<?, ?> data) {
        List<Object> params = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        Matcher m = DIRECTIVE.matcher(template);
        boolean hasWhere = false;
        int last = 0;

        while (m.find()) {
            sb.append(template, last, m.start());
            last = m.end();

            String directive = m.group(1);
            String argsStr = m.group(2);

            switch (directive) {
                case "where" -> {
                    String clause = buildCondition(argsStr, data, params);
                    if (clause != null) {
                        sb.append("WHERE ").append(clause);
                        hasWhere = true;
                    }
                }
                case "and" -> {
                    String clause = buildCondition(argsStr, data, params);
                    if (clause != null) {
                        sb.append(hasWhere ? "AND " : "WHERE ").append(clause);
                        hasWhere = true;
                    }
                }
                case "or" -> {
                    String clause = buildCondition(argsStr, data, params);
                    if (clause != null) {
                        sb.append(hasWhere ? "OR " : "WHERE ").append(clause);
                        hasWhere = true;
                    }
                }
                case "orderBy" -> {
                    String orderClause = buildOrderBy(argsStr, data);
                    if (orderClause != null) {
                        sb.append("ORDER BY ").append(orderClause);
                    }
                }
            }
        }
        sb.append(template, last, template.length());
        return new Parsed(sb.toString().trim(), params.toArray());
    }

    // #where(field, '=', paramName) / #and(field, '>', paramName)
    private static String buildCondition(String argsStr, Map<?, ?> data, List<Object> params) {
        String[] parts = splitArgs(argsStr);
        if (parts.length < 3) return null;

        String field = parts[0].trim();
        String op = parts[1].trim().replace("'", "").replace("\"", "");
        String paramName = parts[2].trim();

        Object value = data.get(paramName);
        if (value == null || (value instanceof String s && s.isBlank())) return null;

        params.add(value);
        return field + " " + op + " ? ";
    }

    // #orderBy(field1, field2, ...) — whitelist: only allow fields present in data
    private static String buildOrderBy(String argsStr, Map<?, ?> data) {
        String[] fields = splitArgs(argsStr);
        List<String> valid = new ArrayList<>();
        for (String f : fields) {
            String field = f.trim();
            if (field.isEmpty()) continue;
            // only allow alphanumeric + underscore to prevent SQL injection
            if (field.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                valid.add(field);
            }
        }
        return valid.isEmpty() ? null : String.join(", ", valid) + " ";
    }

    private static String[] splitArgs(String argsStr) {
        return argsStr.split(",");
    }

    private SqlKit() {}
}
