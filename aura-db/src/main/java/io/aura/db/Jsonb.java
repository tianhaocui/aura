package io.aura.db;

import com.alibaba.fastjson2.JSON;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * A PostgreSQL {@code jsonb} parameter.
 *
 * <p>{@link #of(Object)} serialises the value with fastjson2; {@link #raw(String)} binds JSON
 * text that is already serialised. The parameter is sent with {@link Types#OTHER}, which lets
 * PostgreSQL coerce the unknown value to the target column's or operator's jsonb type -- the
 * same reason a plain String does not bind to a jsonb column.
 *
 * <pre>{@code
 * db.execute("INSERT INTO doc (payload) VALUES (?)", Jsonb.of(Map.of("kind", "note")));
 * db.find("SELECT * FROM doc WHERE payload @> ?", Jsonb.raw("{\"kind\":\"note\"}"));
 * }</pre>
 */
public final class Jsonb implements SqlBindable {

    private final String json;

    private Jsonb(String json) {
        this.json = json;
    }

    /** Serialises {@code value} to JSON. A Java String becomes a JSON string scalar. */
    public static Jsonb of(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof Jsonb jsonb) return jsonb;
        return new Jsonb(JSON.toJSONString(value));
    }

    /** Wraps JSON text that the caller already serialised. */
    public static Jsonb raw(String json) {
        Objects.requireNonNull(json, "json");
        return new Jsonb(json);
    }

    public String json() {
        return json;
    }

    @Override
    public void bind(PreparedStatement statement, int index) throws SQLException {
        statement.setObject(index, json, Types.OTHER);
    }

    @Override
    public String toString() {
        return json;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Jsonb jsonb && json.equals(jsonb.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }
}
