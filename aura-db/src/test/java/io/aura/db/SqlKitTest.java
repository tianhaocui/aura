package io.aura.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SqlKitTest {

    // --- #where ---

    @Test
    void where_withValue_generatesWhereClause() {
        String template = "SELECT * FROM users #where(name, '=', name)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of("name", "Alice"));

        assertThat(result.sql()).contains("WHERE name = ?");
        assertThat(result.params()).containsExactly("Alice");
    }

    @Test
    void where_withNullValue_isSkipped() {
        String template = "SELECT * FROM users #where(name, '=', name)";
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("name", null);
        SqlKit.Parsed result = SqlKit.parse(template, data);

        assertThat(result.sql()).doesNotContain("WHERE");
        assertThat(result.params()).isEmpty();
    }

    @Test
    void where_withBlankString_isSkipped() {
        String template = "SELECT * FROM users #where(name, '=', name)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of("name", "   "));

        assertThat(result.sql()).doesNotContain("WHERE");
        assertThat(result.params()).isEmpty();
    }

    // --- #and ---

    @Test
    void and_withValue_generatesAndClause() {
        String template = "SELECT * FROM users #where(name, '=', name) #and(age, '>', age)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of("name", "Alice", "age", 18));

        assertThat(result.sql()).contains("WHERE name = ?");
        assertThat(result.sql()).contains("AND age > ?");
        assertThat(result.params()).containsExactly("Alice", 18);
    }

    @Test
    void and_withoutPriorWhere_generatesWhereClause() {
        // #where is skipped (null), #and becomes the first condition
        String template = "SELECT * FROM users #where(name, '=', name) #and(age, '>', age)";
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("name", null);
        data.put("age", 18);
        SqlKit.Parsed result = SqlKit.parse(template, data);

        assertThat(result.sql()).contains("WHERE age > ?");
        assertThat(result.sql()).doesNotContain("AND");
        assertThat(result.params()).containsExactly(18);
    }

    // --- #or ---

    @Test
    void or_withValue_generatesOrClause() {
        String template = "SELECT * FROM users #where(name, '=', name) #or(email, '=', email)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of("name", "Alice", "email", "a@b.com"));

        assertThat(result.sql()).contains("WHERE name = ?");
        assertThat(result.sql()).contains("OR email = ?");
        assertThat(result.params()).containsExactly("Alice", "a@b.com");
    }

    // --- #orderBy ---

    @Test
    void orderBy_withValidFields_generatesOrderByClause() {
        String template = "SELECT * FROM users #orderBy(name, age)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of());

        assertThat(result.sql()).contains("ORDER BY name, age");
    }

    @Test
    void orderBy_withSqlInjectionAttempt_filteredOut() {
        String template = "SELECT * FROM users #orderBy(name; DROP TABLE users--)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of());

        assertThat(result.sql()).doesNotContain("DROP");
        assertThat(result.sql()).doesNotContain("ORDER BY");
    }

    @Test
    void orderBy_mixedValidAndInvalid_onlyValidIncluded() {
        String template = "SELECT * FROM users #orderBy(name, 1=1, age)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of());

        assertThat(result.sql()).contains("ORDER BY");
        assertThat(result.sql()).contains("name");
        assertThat(result.sql()).contains("age");
        assertThat(result.sql()).doesNotContain("1=1");
    }

    // --- combined ---

    @Test
    void combined_whereAndAndAndOrderBy() {
        String template = "SELECT * FROM users #where(status, '=', status) #and(age, '>', age) #orderBy(name)";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of("status", "active", "age", 21));

        assertThat(result.sql()).contains("WHERE status = ?");
        assertThat(result.sql()).contains("AND age > ?");
        assertThat(result.sql()).contains("ORDER BY name");
        assertThat(result.params()).containsExactly("active", 21);
    }

    // --- Parsed record ---

    @Test
    void parsed_hasSqlAndParamsArray() {
        SqlKit.Parsed result = SqlKit.parse(
                "SELECT * FROM t #where(x, '=', x)",
                Map.of("x", "val"));

        assertThat(result.sql()).isNotBlank();
        assertThat(result.params()).isNotNull();
        assertThat(result.params()).hasSize(1);
        assertThat(result.params()[0]).isEqualTo("val");
    }

    @Test
    void parsed_noDirectives_returnsSqlUnchanged() {
        String template = "SELECT * FROM users WHERE 1=1";
        SqlKit.Parsed result = SqlKit.parse(template, Map.of());

        assertThat(result.sql()).isEqualTo(template);
        assertThat(result.params()).isEmpty();
    }
}
