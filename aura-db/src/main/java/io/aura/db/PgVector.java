package io.aura.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A pgvector {@code vector} parameter.
 *
 * <p>Values are formatted as pgvector's text form ({@code [1.0,2.5]}) and sent with
 * {@link Types#OTHER}, so PostgreSQL resolves the parameter against the target column or
 * operator ({@code <->}, {@code <#>}, {@code <=>}, ...) without a driver-specific PGobject.
 *
 * <pre>{@code
 * db.execute("INSERT INTO item (embedding) VALUES (?)", PgVector.of(0.1f, 0.2f, 0.3f));
 * Row row = db.findOne("SELECT embedding FROM item WHERE id = ?", id);
 * float[] embedding = row.getVector("embedding");
 * }</pre>
 */
public final class PgVector implements SqlBindable {

    private final float[] values;

    private PgVector(float[] values) {
        this.values = values;
    }

    public static PgVector of(float... values) {
        Objects.requireNonNull(values, "values");
        return checked(values.clone());
    }

    public static PgVector of(double... values) {
        Objects.requireNonNull(values, "values");
        float[] converted = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException("vector values must be finite: " + values[i]);
            }
            converted[i] = (float) values[i];
        }
        return checked(converted);
    }

    /** Accepts a {@code PgVector}, a float/double array, a list of numbers, or pgvector's text form. */
    public static PgVector parse(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof PgVector vector) return vector;
        if (value instanceof float[] floats) return of(floats);
        if (value instanceof double[] doubles) return of(doubles);
        if (value instanceof List<?> list) {
            float[] converted = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "vector list element " + i + " is not a number: " + element);
                }
                converted[i] = number.floatValue();
            }
            return checked(converted);
        }
        return of(parseLiteral(value.toString()));
    }

    public float[] toFloatArray() {
        return values.clone();
    }

    public int dimensions() {
        return values.length;
    }

    public String literal() {
        StringBuilder literal = new StringBuilder(values.length * 8 + 2).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) literal.append(',');
            literal.append(Float.toString(values[i]));
        }
        return literal.append(']').toString();
    }

    @Override
    public void bind(PreparedStatement statement, int index) throws SQLException {
        statement.setObject(index, literal(), Types.OTHER);
    }

    @Override
    public String toString() {
        return literal();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PgVector vector && Arrays.equals(values, vector.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    private static PgVector checked(float[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("vector must have at least one dimension");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("vector values must be finite: " + value);
            }
        }
        return new PgVector(values);
    }

    private static float[] parseLiteral(String literal) {
        String text = literal.trim();
        if (text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            throw new IllegalArgumentException("vector literal must look like [1,2,3]: " + literal);
        }
        String body = text.substring(1, text.length() - 1).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("vector must have at least one dimension");
        }
        String[] parts = body.split(",", -1);
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("vector literal has an empty element: " + literal);
            }
            try {
                values[i] = Float.parseFloat(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "vector literal element is not a number: " + part, e);
            }
        }
        return values;
    }
}
