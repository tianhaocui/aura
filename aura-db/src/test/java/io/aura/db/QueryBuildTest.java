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

    // --- where with null value throws ---

    @Test
    void where_nullValue_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.where("name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    // --- where with blank string throws ---

    @Test
    void where_blankString_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.where("name", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
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

    // --- null and blank throw, whereIf skips correctly ---

    @Test
    void whereIf_nullValue_skipped() throws Exception {
        Query q = newQuery("users");
        q.whereIf(false, "name", null);
        q.where("status", "active");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE status = ?");
    }

    @Test
    void whereIf_trueWithNull_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.whereIf(true, "name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void whereIf_trueWithValidValue_addsCondition() throws Exception {
        Query q = newQuery("users");
        q.whereIf(true, "status", "active");

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
    void orderBy_qualifiedIdentifier_accepted() throws Exception {
        Query q = newQuery("users");
        q.orderBy("t.created_at DESC");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users ORDER BY t.created_at DESC");
    }

    @Test
    void orderBy_sqlInjectionAttempt_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.orderBy("name; DROP TABLE users--"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderBy_mixedValidAndInvalid_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.orderBy("name", "1=1", "age"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderBy_emptyString_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.orderBy(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderBy_multipleQualifiedIdentifiers_accepted() throws Exception {
        Query q = newQuery("orders");
        q.orderBy("t.created_at DESC", "t.id ASC");
        assertThat(buildSql(q)).isEqualTo("SELECT * FROM orders ORDER BY t.created_at DESC, t.id ASC");
    }

    @Test
    void orderBy_caseInsensitiveDirection() throws Exception {
        Query q = newQuery("users");
        q.orderBy("name asc", "age Desc");
        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users ORDER BY name asc, age Desc");
    }

    @Test
    void orderBy_specialCharsInField_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.orderBy("name`"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid orderBy field");
    }

    @Test
    void orderBy_spaceOnlyField_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.orderBy("   "))
                .isInstanceOf(IllegalArgumentException.class);
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

    // --- whereNull / whereNotNull ---

    @Test
    void whereNull_addsIsNullCondition() throws Exception {
        Query q = newQuery("users");
        q.whereNull("deleted_at");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE deleted_at IS NULL");
    }

    @Test
    void whereNotNull_addsIsNotNullCondition() throws Exception {
        Query q = newQuery("users");
        q.whereNotNull("email");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM users WHERE email IS NOT NULL");
    }

    @Test
    void whereNull_combinedWithWhere() throws Exception {
        Query q = newQuery("categories");
        q.where("status", "active").whereNull("parent_id");

        assertThat(buildSql(q)).isEqualTo("SELECT * FROM categories WHERE status = ? AND parent_id IS NULL");
    }

    @Test
    void whereNull_invalidField_throws() throws Exception {
        Query q = newQuery("users");
        assertThatThrownBy(() -> q.whereNull("field; DROP TABLE users--"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
