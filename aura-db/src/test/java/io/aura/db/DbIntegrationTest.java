package io.aura.db;

import io.aura.contract.CancellationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DbIntegrationTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP TABLE IF EXISTS users");
        db.execute("""
            CREATE TABLE users (
                id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                name       VARCHAR(100),
                active     BOOLEAN,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);
    }

    @AfterEach
    void tearDown() {
        db.execute("DROP TABLE IF EXISTS users");
        db.close();
    }

    // --- Row.insert / findById table propagation ---

    @Test
    void findById_rowHasTableSet_canUpdate() {
        Row inserted = Row.of("users").set("name", "Alice").set("active", true).insert(db);
        Object id = inserted.id();
        assertThat(id).isNotNull();

        Row found = db.findById("users", id);
        assertThat(found).isNotNull();
        assertThat(found.table()).isEqualTo("users");
        assertThat(found.getStr("name")).isEqualTo("Alice");

        found.set("name", "Bob");
        boolean updated = found.update(db);
        assertThat(updated).isTrue();

        Row refetched = db.findById("users", id);
        assertThat(refetched.getStr("name")).isEqualTo("Bob");
    }

    @Test
    void queryAndQueryBuilderRejectPreCancelledTokenBeforeOpeningJdbcStatement() {
        CancellationToken token = new CancellationToken();
        token.cancel();

        assertThatThrownBy(() -> db.findCancellable("SELECT * FROM users", token))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThatThrownBy(() -> db.table("users").find(token))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThatThrownBy(() -> db.executeCancellable("DELETE FROM users", token))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void findById_rowHasTableSet_canDelete() {
        Row inserted = Row.of("users").set("name", "ToDelete").set("active", false).insert(db);
        Object id = inserted.id();

        Row found = db.findById("users", id);
        assertThat(found.table()).isEqualTo("users");

        boolean deleted = found.delete(db);
        assertThat(deleted).isTrue();
        assertThat(db.findById("users", id)).isNull();
    }

    @Test
    void findBy_rowsHaveTableSet() {
        Row.of("users").set("name", "Alice").set("active", true).insert(db);
        Row.of("users").set("name", "Bob").set("active", true).insert(db);

        List<Row> rows = db.findWhere("users", "active = ?", true);
        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            assertThat(row.table()).isEqualTo("users");
        }
    }

    // --- Row.insertFull ---

    @Test
    void insertFull_returnsRowWithServerGeneratedColumns() {
        Row row = Row.of("users").set("name", "Charlie").set("active", true).insertFull(db);
        assertThat((Object) row.id()).isNotNull();
        assertThat(row.getStr("name")).isEqualTo("Charlie");
        // created_at should be populated from the DB
        assertThat(row.get("created_at")).isNotNull();
    }

    // --- Timestamp preserved as LocalDateTime (JDBC type, not String) ---

    @Test
    void timestamp_preservedAsLocalDateTime() {
        Row.of("users").set("name", "TimestampTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT * FROM users WHERE name = ?", "TimestampTest");
        assertThat(row).isNotNull();
        Object createdAt = row.get("created_at");
        // stored as LocalDateTime so update() can write it back via JDBC
        assertThat(createdAt).isInstanceOf(java.time.LocalDateTime.class);
    }

    @Test
    void timestamp_getStr_returnsReadableString() {
        Row.of("users").set("name", "StrTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT * FROM users WHERE name = ?", "StrTest");
        String ts = row.getStr("created_at");
        assertThat(ts).isNotNull().isNotBlank();
    }

    // --- Row.insert returns generated key ---

    @Test
    void insert_setsGeneratedId() {
        Row row = Row.of("users").set("name", "KeyTest").set("active", false).insert(db);
        assertThat((Object) row.id()).isNotNull();
    }

    // --- Row.exclude ---

    @Test
    void exclude_preventsColumnFromBeingUpdated() {
        Row inserted = Row.of("users").set("name", "ExcludeTest").set("active", true).insertFull(db);
        Object id = inserted.id();
        String originalCreatedAt = inserted.getStr("created_at");

        // simulate read-modify-write: exclude server-managed column
        Row found = db.findById("users", id);
        found.exclude("created_at").set("name", "Updated");
        found.update(db);

        Row refetched = db.findById("users", id);
        assertThat(refetched.getStr("name")).isEqualTo("Updated");
        assertThat(refetched.getStr("created_at")).isEqualTo(originalCreatedAt);
    }

    // --- Row.update / delete without table throws ---

    @Test
    void update_withoutTable_throwsIllegalState() {
        Row row = Row.of("").set("name", "x").id(1);
        assertThatThrownBy(() -> row.update(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Table name not set");
    }

    @Test
    void delete_withoutTable_throwsIllegalState() {
        Row row = Row.of("").id(1);
        assertThatThrownBy(() -> row.delete(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Table name not set");
    }

    // --- Row dirty update ---

    @Test
    void update_noDirtyFields_updatesAll_backwardsCompatible() {
        Row inserted = Row.of("users").set("name", "Alice").set("active", true).insert(db);
        Row found = db.findById("users", inserted.id());
        // No .set() call after findById → dirtyKeys is empty → falls through to update all fields
        // This preserves backwards-compatible behavior for findById → update roundtrip
        assertThat(found.update(db)).isTrue();
    }

    @Test
    void update_onlyDirtyFieldsSent() {
        Row inserted = Row.of("users").set("name", "Bob").set("active", true).insert(db);
        Row found = db.findById("users", inserted.id());
        // Only change name — dirtyKeys now has "name", so only name is in SET clause
        found.set("name", "Bobby");
        assertThat(found.update(db)).isTrue();

        Row refetched = db.findById("users", inserted.id());
        assertThat(refetched.getStr("name")).isEqualTo("Bobby");
        assertThat(refetched.get("active")).isEqualTo(true);
    }

    @Test
    void update_newRow_withSetOnly_sendsOnlyDirtyFields() {
        // Row.of().set() marks fields dirty — insert doesn't clear them
        // After insert, if we call update, only dirty fields go into SET
        Row row = Row.of("users").set("name", "Charlie").set("active", true).insert(db);
        // dirtyKeys = [name, active], both were .set() before insert
        // Now set only name
        row.set("name", "Charles");
        row.update(db);

        Row refetched = db.findById("users", row.id());
        assertThat(refetched.getStr("name")).isEqualTo("Charles");
    }

    // --- Query.paginate / delete / update ---

    @Test
    void query_paginate_returnsPage() {
        Row.of("users").set("name", "A").set("active", true).insert(db);
        Row.of("users").set("name", "B").set("active", true).insert(db);
        Row.of("users").set("name", "C").set("active", true).insert(db);

        Page<Row> page = db.table("users").where("active", true)
                .orderBy("id ASC").paginate(1, 2);
        assertThat(page.list()).hasSize(2);
        assertThat(page.total()).hasValue(3);
        assertThat(page.totalPages()).hasValue(2);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void query_paginate_page2() {
        Row.of("users").set("name", "A").set("active", true).insert(db);
        Row.of("users").set("name", "B").set("active", true).insert(db);
        Row.of("users").set("name", "C").set("active", true).insert(db);

        Page<Row> page = db.table("users").where("active", true)
                .orderBy("id ASC").paginate(2, 2);
        assertThat(page.list()).hasSize(1);
        assertThat(page.pageNum()).isEqualTo(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void paginate_rejectsInvalidBoundsBeforeQuerying() {
        PaginationOrder order = PaginationOrder.by("id ASC");
        assertThatIllegalArgumentException().isThrownBy(() -> db.paginate("SELECT * FROM users", new Object[0], order, 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> db.paginate("SELECT * FROM users", new Object[0], order, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                db.paginate("SELECT * FROM users", new Object[0], order, 1, Page.MAX_PAGE_SIZE + 1));
    }

    @Test
    void paginate_requiresExplicitStableOrderAndCanSkipTotalQuery() {
        Row.of("users").set("name", "A").set("active", true).insert(db);
        Row.of("users").set("name", "B").set("active", true).insert(db);
        Row.of("users").set("name", "C").set("active", true).insert(db);

        assertThatThrownBy(() -> db.table("users").paginate(1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orderBy");

        Page<Row> page = db.table("users").orderBy("id ASC")
                .paginate(1, 2, TotalStrategy.NONE);
        assertThat(page.list()).hasSize(2);
        assertThat(page.total()).isEmpty();
        assertThat(page.totalPages()).isEmpty();
        assertThat(page.hasNext()).isTrue();

        Page<Row> last = db.table("users").orderBy("id ASC")
                .paginate(2, 2, TotalStrategy.NONE);
        assertThat(last.list()).hasSize(1);
        assertThat(last.total()).isEmpty();
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void totalStrategyControlsWhetherCountQueryRuns() throws Exception {
        org.h2.jdbcx.JdbcDataSource delegate = new org.h2.jdbcx.JdbcDataSource();
        delegate.setURL("jdbc:h2:mem:pagination-count;DB_CLOSE_DELAY=-1");
        delegate.setUser("sa");
        AtomicInteger queryExecutions = new AtomicInteger();
        javax.sql.DataSource counting = countingDataSource(delegate, queryExecutions);
        try (Db counted = Db.create("pagination-count", counting)) {
            counted.execute("CREATE TABLE items(id BIGINT PRIMARY KEY, name VARCHAR(20))");
            counted.execute("INSERT INTO items(id, name) VALUES (1, 'a'), (2, 'b'), (3, 'c')");

            queryExecutions.set(0);
            Page<Row> withoutTotal = counted.paginate(
                    "SELECT * FROM items", new Object[0], PaginationOrder.by("id ASC"),
                    1, 2, TotalStrategy.NONE);
            assertThat(queryExecutions).hasValue(1);
            assertThat(withoutTotal.total()).isEmpty();
            assertThat(withoutTotal.hasNext()).isTrue();

            queryExecutions.set(0);
            Page<Row> exact = counted.paginate(
                    "SELECT * FROM items", new Object[0], PaginationOrder.by("id ASC"),
                    1, 2, TotalStrategy.EXACT);
            assertThat(queryExecutions).hasValue(2);
            assertThat(exact.total()).hasValue(3);

            Page<Row> alreadyOrdered = counted.paginate(
                    "SELECT * FROM items ORDER BY name DESC", new Object[0],
                    PaginationOrder.by("id ASC"), 1, 2, TotalStrategy.NONE);
            assertThat(alreadyOrdered.list()).extracting(row -> row.getLong("id"))
                    .containsExactly(1L, 2L);

            assertThatThrownBy(() -> counted.paginate(
                    "SELECT i.id, i.name FROM items i", new Object[0],
                    PaginationOrder.by("i.id ASC"), 1, 2, TotalStrategy.NONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("projected column label");
        }
    }

    private static javax.sql.DataSource countingDataSource(
            javax.sql.DataSource delegate, AtomicInteger queryExecutions) {
        return new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                return countingConnection(delegate.getConnection(), queryExecutions);
            }
            @Override public java.sql.Connection getConnection(String username, String password)
                    throws java.sql.SQLException {
                return countingConnection(delegate.getConnection(username, password), queryExecutions);
            }
            @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
                return delegate.getLogWriter();
            }
            @Override public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
                delegate.setLogWriter(out);
            }
            @Override public void setLoginTimeout(int seconds) throws java.sql.SQLException {
                delegate.setLoginTimeout(seconds);
            }
            @Override public int getLoginTimeout() throws java.sql.SQLException {
                return delegate.getLoginTimeout();
            }
            @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
                return delegate.getParentLogger();
            }
            @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
                return delegate.unwrap(iface);
            }
            @Override public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
                return delegate.isWrapperFor(iface);
            }
        };
    }

    private static java.sql.Connection countingConnection(
            java.sql.Connection delegate, AtomicInteger queryExecutions) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                DbIntegrationTest.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if ("prepareStatement".equals(method.getName())
                                && result instanceof java.sql.PreparedStatement statement) {
                            return countingStatement(statement, queryExecutions);
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static java.sql.PreparedStatement countingStatement(
            java.sql.PreparedStatement delegate, AtomicInteger queryExecutions) {
        return (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                DbIntegrationTest.class.getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) queryExecutions.incrementAndGet();
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Test
    void query_delete_removesMatchingRows() {
        Row.of("users").set("name", "Keep").set("active", true).insert(db);
        Row.of("users").set("name", "Remove").set("active", false).insert(db);

        int deleted = db.table("users").where("active", false).delete();
        assertThat(deleted).isEqualTo(1);
        assertThat(db.table("users").count()).isEqualTo(1);
    }

    @Test
    void query_update_modifiesMatchingRows() {
        Row.of("users").set("name", "OldName").set("active", true).insert(db);
        Row.of("users").set("name", "Other").set("active", false).insert(db);

        int updated = db.table("users").where("active", true).update(Row.of("users").set("name", "NewName"));
        assertThat(updated).isEqualTo(1);

        Row found = db.table("users").where("name", "NewName").findOne();
        assertThat(found).isNotNull();
    }

    // --- transaction ---

    @Test
    void transaction_rollsBackOnException() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "TxTest").set("active", true).insert(db);
            throw new RuntimeException("rollback");
        })).isInstanceOf(RuntimeException.class);

        Row found = db.findOne("SELECT * FROM users WHERE name = ?", "TxTest");
        assertThat(found).isNull();
    }

    @Test
    void transaction_commitsOnSuccess() {
        db.transaction(() -> {
            Row.of("users").set("name", "TxCommit").set("active", true).insert(db);
        });
        Row found = db.findOne("SELECT * FROM users WHERE name = ?", "TxCommit");
        assertThat(found).isNotNull();
    }

    @Test
    void transactionContext_exposesBoundConnectionAndReusesNestedContext() {
        String result = db.transactionContext(context -> {
            assertThat(context.db()).isSameAs(db);
            assertThat(context.connection()).isNotNull();
            assertThat(db.currentTransactionContext()).isSameAs(context);
            return db.transactionContext(nested -> {
                assertThat(nested).isSameAs(context);
                db.execute("INSERT INTO users (name, active) VALUES (?, ?)", "Context", true);
                return "done";
            });
        });

        assertThat(result).isEqualTo("done");
        assertThat(db.currentTransactionContext()).isNull();
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Context")).isNotNull();
    }

    @Test
    void transaction_nested_reusesOuter() {
        db.transaction(() -> {
            Row.of("users").set("name", "Outer").set("active", true).insert(db);
            db.transaction(() -> {
                Row.of("users").set("name", "Inner").set("active", true).insert(db);
            });
        });
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Outer")).isNotNull();
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Inner")).isNotNull();
    }

    @Test
    void transaction_nested_outerRollbackRollsBothBack() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "Outer2").set("active", true).insert(db);
            db.transaction(() -> {
                Row.of("users").set("name", "Inner2").set("active", true).insert(db);
            });
            throw new RuntimeException("rollback outer");
        }));
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Outer2")).isNull();
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Inner2")).isNull();
    }

    @Test
    void transactionIndependent_independentFromOuter() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "OuterNew").set("active", true).insert(db);
            db.transactionIndependent(() -> {
                Row.of("users").set("name", "InnerNew").set("active", true).insert(db);
            });
            throw new RuntimeException("rollback outer");
        }));
        // outer rolled back
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "OuterNew")).isNull();
        // inner committed independently
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "InnerNew")).isNotNull();
    }

    @Test
    void beginTransaction_manualCommit() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "Manual").set("active", true).insert(db);
            tx.commit();
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Manual")).isNotNull();
    }

    @Test
    void beginTransactionHandle_exposesContextAndLifecycle() {
        try (TransactionHandle handle = db.beginTransactionHandle()) {
            assertThat(handle.isActive()).isTrue();
            assertThat(handle.context()).isSameAs(db.currentTransactionContext());
            db.execute("INSERT INTO users (name, active) VALUES (?, ?)", "Handle", true);
            handle.commit();
            assertThat(handle.isActive()).isFalse();
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Handle")).isNotNull();
    }

    @Test
    void beginTransaction_manualRollback() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "ManualRollback").set("active", true).insert(db);
            tx.rollback();
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "ManualRollback")).isNull();
    }

    @Test
    void beginTransaction_autoCloseRollsBack() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "AutoClose").set("active", true).insert(db);
            // no commit — close() should rollback
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "AutoClose")).isNull();
    }

    @Test
    void query_exists_returnsTrue_whenRowsExist() {
        Row.of("users").set("name", "ExistCheck").set("active", true).insert(db);
        assertThat(db.table("users").where("name", "ExistCheck").exists()).isTrue();
    }

    @Test
    void query_exists_returnsFalse_whenNoRows() {
        assertThat(db.table("users").where("name", "NoSuchUser").exists()).isFalse();
    }

    @Test
    void transactionThrows_propagatesCheckedException() {
        assertThatThrownBy(() -> db.transactionThrows(() -> {
            Row.of("users").set("name", "ThrowsTest").set("active", true).insert(db);
            throw new java.io.IOException("checked");
        })).isInstanceOf(java.io.IOException.class);

        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "ThrowsTest")).isNull();
    }

    @Test
    void transactionThrows_commitsOnSuccess() throws Exception {
        String result = db.transactionThrows(() -> {
            Row.of("users").set("name", "ThrowsOk").set("active", true).insert(db);
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "ThrowsOk")).isNotNull();
    }

    @Test
    void rsToRow_preservesMixedCaseAlias() {
        Row.of("users").set("name", "CaseTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT name AS \"userName\", active AS \"isActive\" FROM users WHERE name = ?", "CaseTest");
        assertThat(row).isNotNull();
        assertThat(row.containsKey("userName")).isTrue();
        assertThat(row.containsKey("isActive")).isTrue();
        assertThat(row.getStr("userName")).isEqualTo("CaseTest");
    }

    @Test
    void query_findOne_preservesColumnCase() {
        Row.of("users").set("name", "QueryCase").set("active", true).insert(db);
        Row row = db.table("users").where("name", "QueryCase").findOne();
        assertThat(row).isNotNull();
        // H2 returns uppercase column labels by default
        assertThat(row.getStr("NAME")).isEqualTo("QueryCase");
        // case-insensitive fallback still works
        assertThat(row.getStr("name")).isEqualTo("QueryCase");
    }
}
