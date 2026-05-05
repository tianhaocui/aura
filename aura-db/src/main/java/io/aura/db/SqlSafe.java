package io.aura.db;

import java.util.Set;
import java.util.regex.Pattern;

final class SqlSafe {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.]*");
    private static final Set<String> VALID_OPS = Set.of(
            "=", "!=", "<>", "<", ">", "<=", ">=",
            "LIKE", "like", "NOT LIKE", "not like",
            "IN", "in", "NOT IN", "not in",
            "IS", "is", "IS NOT", "is not"
    );

    static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }

    static String qualifiedIdentifier(String name) {
        if (name == null || !QUALIFIED_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }

    static String operator(String op) {
        if (op == null || !VALID_OPS.contains(op.trim())) {
            throw new IllegalArgumentException("Invalid SQL operator: " + op);
        }
        return op.trim();
    }

    static String columns(String cols) {
        if (cols == null) return "*";
        if ("*".equals(cols.trim())) return "*";
        for (String part : cols.split(",")) {
            String col = part.trim();
            if (!col.isEmpty() && !IDENTIFIER.matcher(col).matches()) {
                throw new IllegalArgumentException("Invalid column name: " + col);
            }
        }
        return cols;
    }

    private SqlSafe() {}
}
