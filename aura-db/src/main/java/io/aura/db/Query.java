package io.aura.db;

import io.aura.contract.CancellationToken;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Query {

    public static final int MAX_LIMIT = 10_000;
    public static final int MAX_OFFSET = 1_000_000;

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
        if (value == null) throw new IllegalArgumentException("where() value must not be null for field: " + field + ". Use whereIf() for optional conditions.");
        if (value instanceof String s && s.isBlank()) throw new IllegalArgumentException("where() value must not be blank for field: " + field + ". Use whereIf() for optional conditions.");
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

    public Query whereNull(String field) {
        SqlSafe.qualifiedIdentifier(field);
        conditions.add(field + " IS NULL");
        return this;
    }

    public Query whereNotNull(String field) {
        SqlSafe.qualifiedIdentifier(field);
        conditions.add(field + " IS NOT NULL");
        return this;
    }

    public Query orderBy(String... fields) {
        this.orderBy = PaginationOrder.by(fields).clause();
        return this;
    }

    public Query limit(int limit) {
        requireRange("limit", limit, 0, MAX_LIMIT);
        this.limit = limit;
        return this;
    }

    public Query offset(int offset) {
        requireRange("offset", offset, 0, MAX_OFFSET);
        this.offset = offset;
        return this;
    }

    public List<Row> find() {
        return db.query(buildSql(), params.toArray(), rs -> Db.rsToRow(rs, table));
    }

    public List<Row> find(CancellationToken cancellationToken) {
        return db.query(buildSql(), params.toArray(), rs -> Db.rsToRow(rs, table), cancellationToken);
    }

    public Row findOne() {
        this.limit = 1;
        List<Row> list = find();
        return list.isEmpty() ? null : list.get(0);
    }

    public Row findOne(CancellationToken cancellationToken) {
        this.limit = 1;
        List<Row> list = find(cancellationToken);
        return list.isEmpty() ? null : list.get(0);
    }

    public Page<Row> paginate(int pageNum, int pageSize) {
        return paginate(pageNum, pageSize, TotalStrategy.EXACT);
    }

    public Page<Row> paginate(int pageNum, int pageSize, TotalStrategy totalStrategy) {
        if (orderBy == null || orderBy.isBlank()) {
            throw new IllegalStateException(
                    "paginate() requires explicit orderBy(...) with a unique tie-breaker");
        }
        if (limit != null || offset != null) {
            throw new IllegalStateException("paginate() cannot be combined with limit() or offset()");
        }
        return db.paginateBase(buildBaseSql(), params.toArray(),
                new PaginationOrder(orderBy), pageNum, pageSize, totalStrategy,
                rs -> Db.rsToRow(rs, table));
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM " + table + buildWhere();
        Row row = db.findOne(sql, params.toArray());
        return row == null ? 0 : ((Number) row.values().iterator().next()).intValue();
    }

    public int count(CancellationToken cancellationToken) {
        String sql = "SELECT COUNT(*) FROM " + table + buildWhere();
        Row row = db.findOneCancellable(sql, cancellationToken, params.toArray());
        return row == null ? 0 : ((Number) row.values().iterator().next()).intValue();
    }

    public boolean exists() {
        return count() > 0;
    }

    public boolean exists(CancellationToken cancellationToken) {
        return count(cancellationToken) > 0;
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

    public int update(Row row, CancellationToken cancellationToken) {
        var setCols = new ArrayList<String>();
        var allParams = new ArrayList<>();
        for (var entry : row.entrySet()) {
            SqlSafe.identifier(entry.getKey());
            setCols.add(entry.getKey() + " = ?");
            allParams.add(entry.getValue());
        }
        allParams.addAll(params);
        String sql = "UPDATE " + table + " SET " + String.join(", ", setCols) + buildWhere();
        return db.executeCancellable(sql, cancellationToken, allParams.toArray());
    }

    public int delete() {
        String sql = "DELETE FROM " + table + buildWhere();
        return db.execute(sql, params.toArray());
    }

    public int delete(CancellationToken cancellationToken) {
        String sql = "DELETE FROM " + table + buildWhere();
        return db.executeCancellable(sql, cancellationToken, params.toArray());
    }

    private String buildSql() {
        StringBuilder sb = new StringBuilder(buildBaseSql());
        if (orderBy != null && !orderBy.isEmpty()) sb.append(" ORDER BY ").append(orderBy);
        if (limit != null) sb.append(" LIMIT ").append(limit);
        if (offset != null) sb.append(" OFFSET ").append(offset);
        return sb.toString();
    }

    private String buildBaseSql() {
        return "SELECT " + select + " FROM " + table + buildWhere();
    }

    private String buildWhere() {
        if (conditions.isEmpty()) return "";
        return " WHERE " + String.join(" AND ", conditions);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ": " + value);
        }
    }
}
