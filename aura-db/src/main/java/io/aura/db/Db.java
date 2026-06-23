package io.aura.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class Db implements AutoCloseable {

    private final HikariDataSource ds;
    private final String name;
    private static final ThreadLocal<Connection> TX_CONN = new ThreadLocal<>();
    private static final java.util.concurrent.ExecutorService INDEPENDENT_TX_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "aura-tx-independent");
                t.setDaemon(true);
                return t;
            });

    private Db(String name, HikariDataSource ds) {
        this.name = name;
        this.ds = ds;
    }

    public static Db create(String url, String user, String password) {
        return create("default", url, user, password);
    }

    public static Db create(String name, String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("aura-" + name);
        return new Db(name, new HikariDataSource(config));
    }

    public static Db create(javax.sql.DataSource dataSource) {
        return create("default", dataSource);
    }

    public static Db create(String name, javax.sql.DataSource dataSource) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(dataSource);
        config.setPoolName("aura-" + name);
        return new Db(name, new HikariDataSource(config));
    }

    public String name() {
        return name;
    }

    /**
     * Generates IN-clause placeholders for raw SQL.
     * Usage: db.find("SELECT * FROM user WHERE id IN (" + Db.in(ids) + ")", ids.toArray())
     */
    public static String in(Collection<?> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("IN list must not be empty");
        return "?,".repeat(values.size()).substring(0, values.size() * 2 - 1);
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

    public List<Row> findWhere(String table, String where, Object... params) {
        SqlSafe.identifier(table);
        return query("SELECT * FROM " + table + " WHERE " + where, params, rs -> rsToRow(rs, table));
    }

    public int deleteById(String table, Object id) {
        SqlSafe.identifier(table);
        return execute("DELETE FROM " + table + " WHERE id = ?", id);
    }

    // --- dynamic SQL ---

    public List<Row> findDynamic(String template, Map<?, ?> data) {
        SqlKit.Parsed p = SqlKit.parse(template, data);
        return find(p.sql(), p.params());
    }

    public Page<Row> paginateDynamic(String template, Map<?, ?> data, int pageNum, int pageSize) {
        SqlKit.Parsed p = SqlKit.parse(template, data);
        return paginate(p.sql(), p.params(), pageNum, pageSize);
    }

    // null in data map means "skip this field", not "set to NULL". Use Row.update() for explicit nulls.
    public int updateDynamic(String table, Map<String, Object> data, String primaryKey, Object pkValue) {
        SqlSafe.identifier(table);
        SqlSafe.identifier(primaryKey);
        var setCols = new ArrayList<String>();
        var params = new ArrayList<>();
        for (var entry : data.entrySet()) {
            if (entry.getValue() != null) {
                SqlSafe.identifier(entry.getKey());
                setCols.add(entry.getKey() + " = ?");
                params.add(entry.getValue());
            }
        }
        if (setCols.isEmpty()) return 0;
        params.add(pkValue);
        String sql = "UPDATE " + table + " SET " + String.join(", ", setCols) + " WHERE " + primaryKey + " = ?";
        return execute(sql, params.toArray());
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

    public Object insertAndReturnId(String sql, Object... params) {
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

    public int batchInsert(String table, List<Row> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        SqlSafe.identifier(table);
        java.util.LinkedHashSet<String> allCols = new java.util.LinkedHashSet<>();
        for (Row row : rows) {
            for (String key : row.keySet()) {
                SqlSafe.identifier(key);
                allCols.add(key);
            }
        }
        List<String> cols = new ArrayList<>(allCols);
        String colStr = String.join(", ", cols);
        String placeholders = String.join(", ", cols.stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + table + " (" + colStr + ") VALUES (" + placeholders + ")";
        List<Object[]> paramsList = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Object[] params = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                params[i] = row.get(cols.get(i));
            }
            paramsList.add(params);
        }
        int[] results = batch(sql, paramsList);
        int total = 0;
        for (int r : results) total += Math.max(r, 0);
        return total;
    }

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
        transaction(() -> { block.run(); return null; });
    }

    public <T> T transaction(Supplier<T> block) {
        if (TX_CONN.get() != null) {
            return block.get();  // already in transaction, reuse
        }
        return doTransaction(block);
    }

    public void transactionThrows(ThrowingRunnable block) throws Exception {
        transactionThrows(() -> { block.run(); return null; });
    }

    public <T> T transactionThrows(ThrowingSupplier<T> block) throws Exception {
        if (TX_CONN.get() != null) {
            return block.get();
        }
        return doTransactionThrows(block);
    }

    /**
     * Always starts a new independent transaction, even if already inside one.
     * Runs the block in a new thread so it gets its own DB connection.
     * The caller blocks until the inner transaction commits or rolls back.
     *
     * <p><b>Warning:</b> the block runs in a separate thread — any ThreadLocal state
     * from the caller (request context, user info, MDC, etc.) is NOT inherited.
     * If you need that state inside the block, pass it explicitly as a parameter.
     *
     * <p>Typical use case: audit logs or operation records that must commit
     * independently and not be rolled back if the outer transaction fails.
     */
    public void transactionIndependent(Runnable block) {
        transactionIndependent(() -> { block.run(); return null; });
    }

    public <T> T transactionIndependent(Supplier<T> block) {
        var future = new java.util.concurrent.CompletableFuture<T>();
        INDEPENDENT_TX_POOL.submit(() -> {
            try {
                future.complete(doTransaction(block));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new DbException(cause);
        }
    }

    /** Manual transaction control. Caller is responsible for commit/rollback/close. */
    public Transaction beginTransaction() {
        if (TX_CONN.get() != null) {
            throw new IllegalStateException("Already in a transaction; use transaction() to reuse or transactionIndependent() for independent transaction");
        }
        try {
            Connection conn = ds.getConnection();
            conn.setAutoCommit(false);
            TX_CONN.set(conn);
            return new Transaction(conn);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public class Transaction implements AutoCloseable {
        private final Connection conn;
        private boolean done = false;

        private Transaction(Connection conn) {
            this.conn = conn;
        }

        public void commit() {
            if (done) throw new IllegalStateException("Transaction already completed");
            try {
                conn.commit();
            } catch (SQLException e) {
                throw new DbException(e);
            } finally {
                done = true;
                TX_CONN.remove();
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }

        public void rollback() {
            if (done) return;
            try {
                conn.rollback();
            } catch (SQLException e) {
                throw new DbException(e);
            } finally {
                done = true;
                TX_CONN.remove();
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }

        @Override
        public void close() {
            rollback();  // no-op if already committed
        }
    }

    private <T> T doTransaction(Supplier<T> block) {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            TX_CONN.set(conn);
            try {
                T result = block.get();
                conn.commit();
                return result;
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

    private <T> T doTransactionThrows(ThrowingSupplier<T> block) throws Exception {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            TX_CONN.set(conn);
            try {
                T result = block.get();
                conn.commit();
                return result;
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException rollbackEx) { e.addSuppressed(rollbackEx); }
                throw e;
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

    public static void shutdownPool() {
        INDEPENDENT_TX_POOL.shutdownNow();
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

    static Row rsToRow(ResultSet rs) throws SQLException {
        return rsToRow(rs, null, "id");
    }

    static Row rsToRow(ResultSet rs, String table) throws SQLException {
        return rsToRow(rs, table, "id");
    }

    static Row rsToRow(ResultSet rs, String table, String primaryKey) throws SQLException {
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
