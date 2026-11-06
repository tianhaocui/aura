package io.aura.db;

import java.util.StringJoiner;

/** Explicit stable ordering appended by Aura before LIMIT/OFFSET pagination. */
public record PaginationOrder(String clause) {
    private static final String FIELD_PATTERN =
            "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?(\\s+(?i)(ASC|DESC))?";

    public PaginationOrder {
        if (clause == null || clause.isBlank()) {
            throw new IllegalArgumentException("pagination order must not be blank");
        }
        StringJoiner normalized = new StringJoiner(", ");
        for (String field : clause.split(",", -1)) {
            String value = field.trim();
            if (!value.matches(FIELD_PATTERN)) {
                throw new IllegalArgumentException("Invalid orderBy field: " + field);
            }
            normalized.add(value);
        }
        clause = normalized.toString();
    }

    public static PaginationOrder by(String... fields) {
        if (fields == null || fields.length == 0) {
            throw new IllegalArgumentException("pagination order requires at least one field");
        }
        return new PaginationOrder(String.join(", ", fields));
    }

    void requireProjectedColumns() {
        for (String field : clause.split(",")) {
            String identifier = field.trim().split("\\s+", 2)[0];
            if (identifier.contains(".")) {
                throw new IllegalArgumentException(
                        "Raw pagination order must use an unqualified projected column label: "
                                + identifier);
            }
        }
    }
}
