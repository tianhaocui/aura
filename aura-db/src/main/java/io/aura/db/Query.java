package io.aura.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

public class Query {

    private final Db db;
    private final String table;
    private String select = "*";
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();
    private String orderBy;
    private Integer limit;
    private Integer offset;

    Query(Db db, String table) {
        this.db = db;
        this.table = SqlSafe.identifier(table);
    }

    public Query select(String columns) {
        this.select = SqlSafe.columns(columns);
        return this;
    }

    public Query where(String field, String op, Object value) {
        if (value == null) return this;
        if (value instanceof String s && s.isBlank()) return this;
        SqlSafe.qualifiedIdentifier(field);
        SqlSafe.operator(op);
        String upperOp = op.toUpperCase();
        if ((upperOp.equals("IN") || upperOp.equals("NOT IN")) && value instanceof Collection<?> col) {
            if (col.isEmpty()) {
                // IN () is invalid SQL — add always-false condition
                conditions.add("1 = 0");
                return this;
            }
            String placeholders = "?,".repeat(col.size());
            placeholders = placeholders.substring(0, placeholders.length() - 1);
            conditions.add(field + " " + op + " (" + placeholders + ")");
            params.addAll(col);
        } else {
            conditions.add(field + " " + op + " ?");
            params.add(value);
        }
        return this;
    }

    public Query where(String field, Object value) {
        return where(field, "=", value);
    }

    public Query whereIf(boolean condition, String field, Object value) {
        return condition ? where(field, value) : this;
    }

    public Query whereIf(boolean condition, String field, String op, Object value) {
        return condition ? where(field, op, value) : this;
    }

    public Query orderBy(String... fields) {
        StringJoiner sj = new StringJoiner(", ");
        for (String f : fields) {
            if (!f.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?(\\s+(?i)(ASC|DESC))?")) {
                throw new IllegalArgumentException("Invalid orderBy field: " + f);
            }
            sj.add(f);
        }
        this.orderBy = sj.toString();
        return this;
    }

    public Query limit(int limit) {
        this.limit = limit;
        return this;
    }

    public Query offset(int offset) {
        this.offset = offset;
        return this;
    }

    public List<Row> find() {
        return db.query(buildSql(), params.toArray(), rs -> Db.rsToRow(rs, table));
    }

    public Row findOne() {
        this.limit = 1;
        List<Row> list = find();
        return list.isEmpty() ? null : list.get(0);
    }

    public Page<Row> paginate(int pageNum, int pageSize) {
        return db.paginate(buildSql(), params.toArray(), pageNum, pageSize);
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM " + table + buildWhere();
        Row row = db.findOne(sql, params.toArray());
        return row == null ? 0 : ((Number) row.values().iterator().next()).intValue();
    }

    public int update(Row row) {
        var setCols = new ArrayList<String>();
        var allParams = new ArrayList<>();
        for (var entry : row.entrySet()) {
            SqlSafe.identifier(entry.getKey());
            setCols.add(entry.getKey() + " = ?");
            allParams.add(entry.getValue());
        }
        allParams.addAll(params);
        String sql = "UPDATE " + table + " SET " + String.join(", ", setCols) + buildWhere();
        return db.execute(sql, allParams.toArray());
    }

    public int delete() {
        String sql = "DELETE FROM " + table + buildWhere();
        return db.execute(sql, params.toArray());
    }

    private String buildSql() {
        StringBuilder sb = new StringBuilder("SELECT ").append(select).append(" FROM ").append(table);
        sb.append(buildWhere());
        if (orderBy != null && !orderBy.isEmpty()) sb.append(" ORDER BY ").append(orderBy);
        if (limit != null) sb.append(" LIMIT ").append(limit);
        if (offset != null) sb.append(" OFFSET ").append(offset);
        return sb.toString();
    }

    private String buildWhere() {
        if (conditions.isEmpty()) return "";
        return " WHERE " + String.join(" AND ", conditions);
    }
}
