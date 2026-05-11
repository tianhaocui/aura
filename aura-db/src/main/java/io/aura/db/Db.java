package io.aura.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Db implements AutoCloseable {

    private final HikariDataSource ds;
    private static final ThreadLocal<Connection> TX_CONN = new ThreadLocal<>();

    private Db(HikariDataSource ds) {
        this.ds = ds;
    }

    public static Db create(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        return new Db(new HikariDataSource(config));
    }

    // --- query builder ---

    public Query table(String table) {
        return new Query(this, table);
    }

    // --- typed query ---

    public <T> List<T> query(String sql, Object[] params, RowMapper<T> mapper) {
        boolean inTx = TX_CONN.get() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = prepare(conn, sql, params);
                 ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) result.add(mapper.map(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public <T> T queryOne(String sql, Object[] params, RowMapper<T> mapper) {
        List<T> list = query(sql, params, mapper);
        return list.isEmpty() ? null : list.get(0);
    }

    // --- Row query ---

    public List<Row> find(String sql, Object... params) {
        return query(sql, params, Db::rsToRow);
    }

    public Row findOne(String sql, Object... params) {
        List<Row> list = find(sql, params);
        return list.isEmpty() ? null : list.get(0);
    }

    public Row findById(String table, Object id) {
        SqlSafe.identifier(table);
        return queryOne("SELECT * FROM " + table + " WHERE id = ?", new Object[]{id}, rs -> rsToRow(rs, table));
    }

    public Row findById(String table, String primaryKey, Object id) {
        SqlSafe.identifier(table);
        SqlSafe.identifier(primaryKey);
        return queryOne("SELECT * FROM " + table + " WHERE " + primaryKey + " = ?", new Object[]{id}, rs -> rsToRow(rs, table, primaryKey));
    }

    public List<Row> findBy(String table, String where, Object... params) {
        SqlSafe.identifier(table);
        return query("SELECT * FROM " + table + " WHERE " + where, params, rs -> rsToRow(rs, table));
    }

    public int deleteById(String table, Object id) {
        SqlSafe.identifier(table);
        return execute("DELETE FROM " + table + " WHERE id = ?", id);
    }

    // --- dynamic SQL ---

    public List<Row> find(String template, Map<?, ?> data) {
        SqlKit.Parsed p = SqlKit.parse(template, data);
        return find(p.sql(), p.params());
    }

    public Page<Row> paginate(String template, Map<?, ?> data, int pageNum, int pageSize) {
        SqlKit.Parsed p = SqlKit.parse(template, data);
        return paginate(p.sql(), p.params(), pageNum, pageSize);
    }

    // --- pagination ---

    public <T> Page<T> paginate(String sql, Object[] params, int pageNum, int pageSize,
                                 RowMapper<T> mapper) {
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") _t";
        long total = queryOne(countSql, params, rs -> rs.getLong(1));
        if (total == 0) return Page.empty(pageNum, pageSize);

        long offset = (long) (pageNum - 1) * pageSize;
        String pageSql = sql + " LIMIT ? OFFSET ?";
        Object[] pageParams = new Object[params.length + 2];
        System.arraycopy(params, 0, pageParams, 0, params.length);
        pageParams[params.length] = pageSize;
        pageParams[params.length + 1] = offset;

        List<T> list = query(pageSql, pageParams, mapper);
        return new Page<>(list, pageNum, pageSize, total);
    }

    public Page<Row> paginate(String sql, Object[] params, int pageNum, int pageSize) {
        return paginate(sql, params, pageNum, pageSize, Db::rsToRow);
    }

    // --- execute ---

    public int execute(String sql, Object... params) {
        boolean inTx = TX_CONN.get() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = prepare(conn, sql, params)) {
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public Object executeAndReturnKey(String sql, Object... params) {
        boolean inTx = TX_CONN.get() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                if (params != null) {
                    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                }
                ps.executeUpdate();
                try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getObject(1) : null;
                }
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    // --- batch ---

    public int[] batch(String sql, List<Object[]> paramsList) {
        boolean inTx = TX_CONN.get() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] params : paramsList) {
                    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    // --- transaction ---

    public void transaction(Runnable block) {
        if (TX_CONN.get() != null) {
            throw new IllegalStateException("Nested transactions are not supported");
        }
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            TX_CONN.set(conn);
            try {
                block.run();
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException rollbackEx) { e.addSuppressed(rollbackEx); }
                throw e instanceof RuntimeException re ? re : new DbException(e);
            } finally {
                TX_CONN.remove();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    @Override
    public void close() {
        ds.close();
    }

    // --- internal ---

    private Connection getConnection() throws SQLException {
        Connection tx = TX_CONN.get();
        return tx != null ? tx : ds.getConnection();
    }

    private PreparedStatement prepare(Connection conn, String sql, Object[] params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        if (params != null) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    private static Row rsToRow(ResultSet rs) throws SQLException {
        return rsToRow(rs, null, "id");
    }

    private static Row rsToRow(ResultSet rs, String table) throws SQLException {
        return rsToRow(rs, table, "id");
    }

    private static Row rsToRow(ResultSet rs, String table, String primaryKey) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        Row row = table != null ? Row.of(table, primaryKey) : Row.of("");
        for (int i = 1; i <= cols; i++) {
            Object val = rs.getObject(i);
            if (val instanceof java.sql.Timestamp ts) {
                val = ts.toLocalDateTime();
            } else if (val instanceof java.sql.Date d) {
                val = d.toLocalDate();
            } else if (val instanceof java.sql.Time t) {
                val = t.toLocalTime();
            }
            row.put(meta.getColumnLabel(i).toLowerCase(), val);
        }
        return row;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static class DbException extends RuntimeException {
        public DbException(Throwable cause) { super(cause); }
    }
}
