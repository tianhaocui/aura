package io.aura.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Row extends LinkedHashMap<String, Object> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Row.class);
    private static final java.math.BigDecimal INT_MIN = java.math.BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final java.math.BigDecimal INT_MAX = java.math.BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final java.math.BigDecimal LONG_MIN = java.math.BigDecimal.valueOf(Long.MIN_VALUE);
    private static final java.math.BigDecimal LONG_MAX = java.math.BigDecimal.valueOf(Long.MAX_VALUE);

    private String table;
    private String primaryKey = "id";
    private final java.util.Set<String> excluded = new java.util.HashSet<>();
    private final java.util.Set<String> dirtyKeys = new java.util.LinkedHashSet<>();

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
        for (String col : cols) excluded.add(col);
        return this;
    }

    public Row set(String key, Object value) {
        SqlSafe.identifier(key);
        put(key, value);
        dirtyKeys.add(key);
        return this;
    }

    @Override
    public Object get(Object key) {
        Object v = super.get(key);
        if (v != null || super.containsKey(key)) return v;
        if (key instanceof String k) {
            for (var entry : entrySet()) {
                if (k.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
        }
        return null;
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

    public String getStr(String key, String defaultValue) {
        Object v = get(key);
        return v == null ? defaultValue : v.toString();
    }

    public Integer getInt(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Number n) return intValue(key, n);
        return Integer.parseInt(v.toString());
    }

    public int getInt(String key, int defaultValue) {
        Object v = get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return intValue(key, n);
        return Integer.parseInt(v.toString());
    }

    public Long getLong(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Number n) return longValue(key, n);
        return Long.parseLong(v.toString());
    }

    public long getLong(String key, long defaultValue) {
        Object v = get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return longValue(key, n);
        return Long.parseLong(v.toString());
    }

    public Double getDouble(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    public double getDouble(String key, double defaultValue) {
        Object v = get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    public Boolean getBool(String key) {
        Object v = get(key);
        if (v == null) return null;
        return boolValue(v);
    }

    public boolean getBool(String key, boolean defaultValue) {
        Object v = get(key);
        if (v == null) return defaultValue;
        return boolValue(v);
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    private static int intValue(String key, Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) return value.intValue();
        if (value instanceof java.math.BigInteger integer) {
            if (integer.bitLength() < 32) return integer.intValue();
            return truncatedInt(key, value);
        }
        if (value instanceof java.math.BigDecimal decimal) {
            if (isIntegral(decimal, INT_MIN, INT_MAX)) return value.intValue();
            return truncatedInt(key, value);
        }
        if (value instanceof Long number) {
            if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return value.intValue();
            return truncatedInt(key, value);
        }
        double d = value.doubleValue();
        // Doubles cannot represent Integer.MAX_VALUE exactly; 2^31 itself is the first out-of-range value.
        if (d != Math.rint(d) || d < -2147483648.0 || d >= 2147483648.0) return truncatedInt(key, value);
        return value.intValue();
    }

    private static int truncatedInt(String key, Number value) {
        log.warn("Row.getInt('{}') converts {} with truncation or overflow", key, value);
        return value.intValue();
    }

    private static long longValue(String key, Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.longValue();
        }
        if (value instanceof java.math.BigInteger integer) {
            if (integer.bitLength() < 64) return integer.longValue();
            return truncatedLong(key, value);
        }
        if (value instanceof java.math.BigDecimal decimal) {
            if (isIntegral(decimal, LONG_MIN, LONG_MAX)) return value.longValue();
            return truncatedLong(key, value);
        }
        double d = value.doubleValue();
        // 2^63 is representable as a double while Long.MAX_VALUE is not, so the upper bound is exclusive.
        if (d != Math.rint(d) || d < -0x1p63 || d >= 0x1p63) return truncatedLong(key, value);
        return value.longValue();
    }

    private static long truncatedLong(String key, Number value) {
        log.warn("Row.getLong('{}') converts {} with truncation or overflow", key, value);
        return value.longValue();
    }

    private static boolean isIntegral(java.math.BigDecimal value, java.math.BigDecimal min,
                                      java.math.BigDecimal max) {
        return value.stripTrailingZeros().scale() <= 0 && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /** The raw JSON text of a jsonb column, whether the driver returned a String or a PGobject. */
    public String getJson(String key) {
        Object value = get(key);
        if (value == null) return null;
        if (value instanceof Jsonb jsonb) return jsonb.json();
        return value.toString();
    }

    /** Parses a jsonb column with fastjson2. {@code String.class} returns the raw text. */
    public <T> T getJson(String key, Class<T> type) {
        java.util.Objects.requireNonNull(type, "type");
        String json = getJson(key);
        if (json == null) return null;
        if (type == String.class) return type.cast(json);
        return com.alibaba.fastjson2.JSON.parseObject(json, type);
    }

    /** Reads a pgvector column as a float array, accepting the driver's text form. */
    public float[] getVector(String key) {
        Object value = get(key);
        return value == null ? null : PgVector.parse(value).toFloatArray();
    }

    public Row setJson(String key, Object value) {
        return set(key, Jsonb.of(value));
    }

    public Row setVector(String key, float... values) {
        return set(key, PgVector.of(values));
    }

    public String table() { return table; }
    public String primaryKey() { return primaryKey; }


    public static int batchInsert(Db db, List<Row> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        String table = rows.get(0).table();
        if (table == null) throw new IllegalStateException("Table name not set on first row");
        return db.batchInsert(table, rows);
    }

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
            String key = entry.getKey();
            if (key.equals(primaryKey)) continue;
            if (excluded.contains(key)) continue;
            if (!dirtyKeys.isEmpty() && !dirtyKeys.contains(key)) continue;
            SqlSafe.identifier(key);
            setCols.add(key + " = ?");
            params.add(entry.getValue());
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
