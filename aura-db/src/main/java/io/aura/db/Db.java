package io.aura.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.aura.contract.CancellationToken;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

public class Db implements AutoCloseable {

    private final HikariDataSource ds;
    private final String name;
    private static final int INDEPENDENT_TX_THREADS = Math.max(2,
            Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int INDEPENDENT_TX_QUEUE_CAPACITY = 256;
    private static final AtomicInteger INDEPENDENT_TX_THREAD_ID = new AtomicInteger();
    private static final AtomicLong INDEPENDENT_TX_REJECTED = new AtomicLong();
    private static final AtomicLong INDEPENDENT_TX_TIMED_OUT = new AtomicLong();
    private final ThreadLocal<TransactionContext> txContext = new ThreadLocal<>();
    private static final ThreadPoolExecutor INDEPENDENT_TX_POOL = new ThreadPoolExecutor(
            INDEPENDENT_TX_THREADS,
            INDEPENDENT_TX_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(INDEPENDENT_TX_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "aura-tx-independent-" + INDEPENDENT_TX_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

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
        return query(sql, params, mapper, new CancellationToken());
    }

    /** Executes a query that can cancel its JDBC statement through the token. */
    public <T> List<T> query(String sql, Object[] params, RowMapper<T> mapper,
                             CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancelled();
        boolean inTx = transactionConnection() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = prepare(conn, sql, params)) {
                CancellationToken.Registration registration = cancellationToken.onCancel(() -> cancel(ps));
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rs.next()) {
                        cancellationToken.throwIfCancelled();
                        result.add(mapper.map(rs));
                    }
                    return result;
                } finally {
                    registration.close();
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (SQLException e) {
            if (cancellationToken.isCancelled()) {
                throw new java.util.concurrent.CancellationException("Database query cancelled");
            }
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public <T> T queryOne(String sql, Object[] params, RowMapper<T> mapper) {
        return queryOne(sql, params, mapper, new CancellationToken());
    }

    public <T> T queryOne(String sql, Object[] params, RowMapper<T> mapper,
                          CancellationToken cancellationToken) {
        List<T> list = query(sql, params, mapper, cancellationToken);
        return list.isEmpty() ? null : list.get(0);
    }

    // --- Row query ---

    public List<Row> find(String sql, Object... params) {
        return query(sql, params, Db::rsToRow);
    }

    public List<Row> findCancellable(String sql, CancellationToken cancellationToken, Object... params) {
        return query(sql, params, Db::rsToRow, cancellationToken);
    }

    public Row findOne(String sql, Object... params) {
        List<Row> list = find(sql, params);
        return list.isEmpty() ? null : list.get(0);
    }

    public Row findOneCancellable(String sql, CancellationToken cancellationToken, Object... params) {
        List<Row> list = findCancellable(sql, cancellationToken, params);
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
        String safeWhere = SqlSafe.whereClause(where);
        return query("SELECT * FROM " + table + " WHERE " + safeWhere, params, rs -> rsToRow(rs, table));
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
        requirePagination(pageNum, pageSize);
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
        return executeInternal(sql, new CancellationToken(), params);
    }

    /** Executes an update that can cancel its JDBC statement through the token. */
    public int executeCancellable(String sql, CancellationToken cancellationToken, Object... params) {
        return executeInternal(sql, cancellationToken, params);
    }

    private int executeInternal(String sql, CancellationToken cancellationToken, Object... params) {
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancelled();
        boolean inTx = transactionConnection() != null;
        Connection conn = null;
        try {
            conn = getConnection();
            try (PreparedStatement ps = prepare(conn, sql, params)) {
                CancellationToken.Registration registration = cancellationToken.onCancel(() -> cancel(ps));
                try {
                    return ps.executeUpdate();
                } finally {
                    registration.close();
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (SQLException e) {
            if (cancellationToken.isCancelled()) {
                throw new java.util.concurrent.CancellationException("Database update cancelled");
            }
            throw new DbException(e);
        } finally {
            if (!inTx && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public Object insertAndReturnId(String sql, Object... params) {
        boolean inTx = transactionConnection() != null;
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
        boolean inTx = transactionConnection() != null;
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
        Objects.requireNonNull(block, "block");
        if (isCurrentTransaction()) {
            return block.get();  // already in transaction, reuse
        }
        return doTransaction(block);
    }

    /**
     * Transaction variant that passes an explicit context to the callback.
     * This overload lets callers use {@code db.transaction(ctx -> ...)} while
     * retaining the original no-argument Supplier/Runnable APIs.
     */
    public <T> T transaction(Function<TransactionContext, T> block) {
        return transactionContext(block);
    }

    /**
     * Runs a transaction with an explicit context for code that must access the
     * transaction-scoped JDBC connection. Nested calls reuse the same context.
     */
    public <T> T transactionContext(Function<TransactionContext, T> block) {
        Objects.requireNonNull(block, "block");
        TransactionContext current = currentTransactionContext();
        if (current != null) return block.apply(current);
        return doTransaction(() -> block.apply(requireCurrentTransactionContext()));
    }

    /** Returns this database's active transaction context, or {@code null}. */
    public TransactionContext currentTransactionContext() {
        return txContext.get();
    }

    public void transactionThrows(ThrowingRunnable block) throws Exception {
        transactionThrows(() -> { block.run(); return null; });
    }

    public <T> T transactionThrows(ThrowingSupplier<T> block) throws Exception {
        Objects.requireNonNull(block, "block");
        if (isCurrentTransaction()) {
            return block.get();
        }
        return doTransactionThrows(block);
    }

    public <T> T transactionContextThrows(ThrowingTransactionFunction<T> block) throws Exception {
        Objects.requireNonNull(block, "block");
        TransactionContext current = currentTransactionContext();
        if (current != null) return block.apply(current);
        return doTransactionThrows(() -> block.apply(requireCurrentTransactionContext()));
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
        return transactionIndependent(Duration.ZERO, new CancellationToken(), ignored -> block.get());
    }

    /** Runs an independent transaction with cooperative cancellation and an optional absolute timeout. */
    public <T> T transactionIndependent(Duration timeout, CancellationToken cancellationToken,
                                        Function<CancellationToken, T> block) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout cannot be negative");
        cancellationToken.throwIfCancelled();
        java.util.concurrent.Future<T> future;
        try {
            future = INDEPENDENT_TX_POOL.submit(() -> {
                cancellationToken.throwIfCancelled();
                return doTransaction(() -> block.apply(cancellationToken));
            });
        } catch (RejectedExecutionException e) {
            INDEPENDENT_TX_REJECTED.incrementAndGet();
            throw new DbException("Independent transaction executor is saturated or shut down", e);
        }
        long deadline = timeout.isZero() ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        CancellationToken.Registration registration = cancellationToken.onCancel(() -> future.cancel(true));
        try {
            while (true) {
                cancellationToken.throwIfCancelled();
                long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                if (remaining <= 0) {
                    INDEPENDENT_TX_TIMED_OUT.incrementAndGet();
                    cancellationToken.cancel();
                    future.cancel(true);
                    throw new DbException("Independent transaction timed out");
                }
                try {
                    long waitNanos = remaining == Long.MAX_VALUE
                            ? TimeUnit.MILLISECONDS.toNanos(100)
                            : Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100));
                    return future.get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException polling) {
                    // Recheck the cancellation token and absolute deadline.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DbException("Independent transaction interrupted", interrupted);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new DbException(cause);
        } finally {
            registration.close();
        }
    }

    public static io.aura.contract.ExecutorMetrics independentTransactionMetrics() {
        return new io.aura.contract.ExecutorMetrics("db-independent-transaction", true,
                INDEPENDENT_TX_POOL.getMaximumPoolSize(), INDEPENDENT_TX_POOL.getActiveCount(),
                INDEPENDENT_TX_POOL.getQueue().size(), INDEPENDENT_TX_QUEUE_CAPACITY,
                INDEPENDENT_TX_POOL.getCompletedTaskCount(), INDEPENDENT_TX_REJECTED.get(),
                INDEPENDENT_TX_TIMED_OUT.get());
    }

    /** Manual transaction control. Caller is responsible for commit/rollback/close. */
    public Transaction beginTransaction() {
        if (isCurrentTransaction()) {
            throw new IllegalStateException("Already in a transaction; use transaction() to reuse or transactionIndependent() for independent transaction");
        }
        try {
            Connection conn = ds.getConnection();
            conn.setAutoCommit(false);
            Transaction tx = new Transaction(conn);
            txContext.set(new TransactionContext(this, conn));
            return tx;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Explicitly typed alias for callers that do not depend on the nested Transaction class. */
    public TransactionHandle beginTransactionHandle() {
        return beginTransaction();
    }

    public class Transaction implements TransactionHandle {
        private final Connection conn;
        private boolean done = false;

        private Transaction(Connection conn) {
            this.conn = conn;
        }

        @Override
        public TransactionContext context() {
            TransactionContext context = currentTransactionContext();
            if (context == null || context.connection() != conn) {
                throw new IllegalStateException("Transaction is not active on the current thread");
            }
            return context;
        }

        @Override
        public boolean isActive() {
            return !done;
        }

        public void commit() {
            if (done) throw new IllegalStateException("Transaction already completed");
            try {
                conn.commit();
            } catch (SQLException e) {
                throw new DbException(e);
            } finally {
                done = true;
                clearTransactionContext(conn);
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
                clearTransactionContext(conn);
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
            txContext.set(new TransactionContext(this, conn));
            try {
                T result = block.get();
                conn.commit();
                return result;
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException rollbackEx) { e.addSuppressed(rollbackEx); }
                throw e instanceof RuntimeException re ? re : new DbException(e);
            } finally {
                clearTransactionContext(conn);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private <T> T doTransactionThrows(ThrowingSupplier<T> block) throws Exception {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            txContext.set(new TransactionContext(this, conn));
            try {
                T result = block.get();
                conn.commit();
                return result;
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException rollbackEx) { e.addSuppressed(rollbackEx); }
                throw e;
            } finally {
                clearTransactionContext(conn);
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

    public MigrationResult migrate(String location) {
        return new MigrationRunner(this).run(new ClasspathMigrationSource(location));
    }

    public MigrationResult migrate(MigrationSource source) {
        return new MigrationRunner(this).run(source);
    }

    // --- internal ---

    private Connection getConnection() throws SQLException {
        Connection tx = transactionConnection();
        return tx != null ? tx : ds.getConnection();
    }

    private boolean isCurrentTransaction() {
        return currentTransactionContext() != null;
    }

    private TransactionContext requireCurrentTransactionContext() {
        TransactionContext context = currentTransactionContext();
        if (context == null) throw new IllegalStateException("No active transaction");
        return context;
    }

    private Connection transactionConnection() {
        TransactionContext context = currentTransactionContext();
        return context == null ? null : context.connection();
    }

    private void clearTransactionContext(Connection connection) {
        TransactionContext context = txContext.get();
        if (context != null && context.connection() == connection) {
            txContext.remove();
        }
    }

    private static void requirePagination(int pageNum, int pageSize) {
        if (pageNum < 1) throw new IllegalArgumentException("pageNum must be at least 1: " + pageNum);
        if (pageSize < 1 || pageSize > Page.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + Page.MAX_PAGE_SIZE + ": " + pageSize);
        }
    }

    private PreparedStatement prepare(Connection conn, String sql, Object[] params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        if (params != null) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    private static void cancel(java.sql.Statement statement) {
        try { statement.cancel(); } catch (SQLException ignored) { }
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
            row.put(meta.getColumnLabel(i), val);
        }
        return row;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static class DbException extends RuntimeException {
        public DbException(Throwable cause) { super(cause); }
        public DbException(String message) { super(message); }
        public DbException(String message, Throwable cause) { super(message, cause); }
    }
}
