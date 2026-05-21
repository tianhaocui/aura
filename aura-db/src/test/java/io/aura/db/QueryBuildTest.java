package io.aura.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class QueryBuildTest {

    // Helper: create a Query without a real Db using the package-private constructor via reflection
    private Query newQuery(String table) throws Exception {
        Constructor<Query> ctor = Query.class.getDeclaredConstructor(Db.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, table);
    }

    // Helper: invoke private buildSql() via reflection
    private String buildSql(Query q) throws Exception {
        Method m = Query.class.getDeclaredMethod("buildSql");
        m.setAccessible(true);
        return (String) m.invoke(q);
    }

    // --- where with null value is skipped ---

    @Test
    void where_nullValue_isSkipped() throws Exception {
        Query q = newQuery("users");
        Query returned = q.where("name", null);

        // returns this (fluent)
        assertThat(returned).isSameAs(q);

        // no WHERE in generated SQL
        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users");
    }

    // --- where with blank string is skipped ---

    @Test
    void where_blankString_isSkipped() throws Exception {
        Query q = newQuery("users");
        q.where("name", "   ");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users");
    }

    // --- where with valid value adds condition ---

    @Test
    void where_validValue_addsCondition() throws Exception {
        Query q = newQuery("users");
        q.where("name", "Alice");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE name = ?");
    }

    // --- where with explicit operator ---

    @Test
    void where_withOperator_usesOperator() throws Exception {
        Query q = newQuery("users");
        q.where("age", ">", 18);

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE age > ?");
    }

    // --- multiple where calls accumulate with AND ---

    @Test
    void multipleWhere_accumulatesWithAnd() throws Exception {
        Query q = newQuery("users");
        q.where("status", "active");
        q.where("age", ">", 21);

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE status = ? AND age > ?");
    }

    // --- null and blank mixed with valid conditions ---

    @Test
    void where_nullAndBlankSkipped_validKept() throws Exception {
        Query q = newQuery("users");
        q.where("name", null);
        q.where("email", "  ");
        q.where("status", "active");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE status = ?");
    }

    // --- orderBy validates field names ---

    @Test
    void orderBy_validField_included() throws Exception {
        Query q = newQuery("users");
        q.orderBy("name");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users ORDER BY name");
    }

    @Test
    void orderBy_validFieldWithDirection_included() throws Exception {
        Query q = newQuery("users");
        q.orderBy("created_at DESC");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users ORDER BY created_at DESC");
    }

    @Test
    void orderBy_sqlInjectionAttempt_filtered() throws Exception {
        Query q = newQuery("users");
        q.orderBy("name; DROP TABLE users--");

        // invalid field is rejected, no ORDER BY in output
        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users");
    }

    @Test
    void orderBy_mixedValidAndInvalid_onlyValidKept() throws Exception {
        Query q = newQuery("users");
        q.orderBy("name", "1=1", "age");

        String sql = buildSql(q);
        assertThat(sql).contains("ORDER BY");
        assertThat(sql).contains("name");
        assertThat(sql).contains("age");
        assertThat(sql).doesNotContain("1=1");
    }

    // --- select ---

    @Test
    void select_changesSelectedColumns() throws Exception {
        Query q = newQuery("users");
        q.select("id, name");

        assertThat(buildSql(q)).isEqualTo("SELECT id, name FROM users");
    }

    // --- limit and offset ---

    @Test
    void limit_appendsLimitClause() throws Exception {
        Query q = newQuery("users");
        q.limit(10);

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users LIMIT 10");
    }

    @Test
    void offset_appendsOffsetClause() throws Exception {
        Query q = newQuery("users");
        q.limit(10).offset(20);

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users LIMIT 10 OFFSET 20");
    }

    // --- combined ---

    @Test
    void combined_whereOrderByLimit() throws Exception {
        Query q = newQuery("products");
        q.where("category", "electronics")
         .where("price", ">", 100)
         .orderBy("price DESC")
         .limit(5);

        assertThat(buildSql(q))
                .isEqualTo("SELECT * FROM products WHERE category = ? AND price > ? ORDER BY price DESC LIMIT 5");
    }

    // --- IN operator ---

    @Test
    void where_inList_expandsPlaceholders() throws Exception {
        Query q = newQuery("users");
        q.where("id", "IN", List.of(1, 2, 3));

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE id IN (?,?,?)");
    }

    @Test
    void where_notInList_expandsPlaceholders() throws Exception {
        Query q = newQuery("users");
        q.where("status", "NOT IN", List.of("banned", "deleted"));

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE status NOT IN (?,?)");
    }

    @Test
    void where_inSingleElement_works() throws Exception {
        Query q = newQuery("users");
        q.where("id", "IN", List.of(42));

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE id IN (?)");
    }

    @Test
    void where_inEmptyList_addsFalseCondition() throws Exception {
        Query q = newQuery("users");
        q.where("id", "IN", List.of());

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE 1 = 0");
    }

    @Test
    void where_inWithOtherConditions_combinesCorrectly() throws Exception {
        Query q = newQuery("users");
        q.where("status", "active")
         .where("id", "IN", List.of(1, 2, 3));

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE status = ? AND id IN (?,?,?)");
    }

    // --- Db.in() helper ---

    @Test
    void dbIn_generatesCorrectPlaceholders() {
        assertThat(Db.in(List.of(1, 2, 3))).isEqualTo("?,?,?");
        assertThat(Db.in(List.of("a"))).isEqualTo("?");
        assertThat(Db.in(List.of(1, 2))).isEqualTo("?,?");
    }

    @Test
    void dbIn_emptyList_throws() {
        assertThatThrownBy(() -> Db.in(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
