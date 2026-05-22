package io.aura.db;

import java.util.LinkedHashMap;
import java.util.Map;

public class Row extends LinkedHashMap<String, Object> {

    private String table;
    private String primaryKey = "id";
    private final java.util.Set<String> excluded = new java.util.HashSet<>();

    private Row() {}

    public static Row of(String table) {
        Row row = new Row();
        if (table != null && !table.isEmpty()) {
            row.table = SqlSafe.identifier(table);
        }
        return row;
    }

    public static Row of(String table, String primaryKey) {
        Row row = new Row();
        row.table = SqlSafe.identifier(table);
        row.primaryKey = SqlSafe.identifier(primaryKey);
        return row;
    }

    public Row exclude(String... cols) {
        for (String col : cols) excluded.add(col.toLowerCase());
        return this;
    }

    public Row set(String key, Object value) {
        SqlSafe.identifier(key);
        put(key.toLowerCase(), value);
        return this;
    }

    public Row id(Object id) {
        put(primaryKey, id);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T id() {
        return (T) idValue();
    }

    public String getStr(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }

    public Integer getInt(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    public Long getLong(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    public Boolean getBool(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    public String table() { return table; }
    public String primaryKey() { return primaryKey; }


    public Row insert(Db db) {
        if (table == null) throw new IllegalStateException("Table name not set");
        var cols = new java.util.ArrayList<>(keySet());
        cols.forEach(SqlSafe::identifier);
        var vals = cols.stream().map(this::get).toArray();
        String colStr = String.join(", ", cols);
        String placeholders = String.join(", ", cols.stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + table + " (" + colStr + ") VALUES (" + placeholders + ")";
        Object generatedKey = db.insertAndReturnId(sql, vals);
        if (generatedKey != null) {
            put(primaryKey, generatedKey);
        }
        return this;
    }

    public Row insertFull(Db db) {
        insert(db);
        Object id = idValue();
        if (id != null) {
            Row fetched = db.findById(table, primaryKey, id);
            if (fetched != null) putAll(fetched);
        }
        return this;
    }

    public boolean update(Db db) {
        if (table == null) throw new IllegalStateException("Table name not set");
        Object idVal = idValue();
        if (idVal == null) throw new IllegalStateException("Primary key value not set");
        var setCols = new java.util.ArrayList<String>();
        var params = new java.util.ArrayList<>();
        for (var entry : entrySet()) {
            if (!entry.getKey().equals(primaryKey) && !excluded.contains(entry.getKey())) {
                SqlSafe.identifier(entry.getKey());
                setCols.add(entry.getKey() + " = ?");
                params.add(entry.getValue());
            }
        }
        if (setCols.isEmpty()) return false;
        params.add(idVal);
        String sql = "UPDATE " + table + " SET " + String.join(", ", setCols)
                + " WHERE " + primaryKey + " = ?";
        return db.execute(sql, params.toArray()) > 0;
    }

    public boolean delete(Db db) {
        if (table == null) throw new IllegalStateException("Table name not set");
        Object idVal = idValue();
        if (idVal == null) throw new IllegalStateException("Primary key value not set");
        String sql = "DELETE FROM " + table + " WHERE " + primaryKey + " = ?";
        return db.execute(sql, idVal) > 0;
    }

    private Object idValue() {
        return get(primaryKey);
    }
}
