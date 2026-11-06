package io.aura.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SqlSafeTest {

    // --- identifier ---

    @Test
    void identifier_validSimpleName() {
        assertThat(SqlSafe.identifier("user")).isEqualTo("user");
        assertThat(SqlSafe.identifier("user_name")).isEqualTo("user_name");
        assertThat(SqlSafe.identifier("_private")).isEqualTo("_private");
        assertThat(SqlSafe.identifier("Table1")).isEqualTo("Table1");
    }

    @Test
    void identifier_rejectsNull() {
        assertThatThrownBy(() -> SqlSafe.identifier(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifier_rejectsEmpty() {
        assertThatThrownBy(() -> SqlSafe.identifier(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifier_rejectsStartsWithNumber() {
        assertThatThrownBy(() -> SqlSafe.identifier("1table"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifier_rejectsSqlInjection() {
        assertThatThrownBy(() -> SqlSafe.identifier("user; DROP TABLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifier_rejectsDot() {
        assertThatThrownBy(() -> SqlSafe.identifier("schema.table"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifier_rejectsSpaces() {
        assertThatThrownBy(() -> SqlSafe.identifier("user name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- qualifiedIdentifier ---

    @Test
    void qualifiedIdentifier_allowsDot() {
        assertThat(SqlSafe.qualifiedIdentifier("schema.table")).isEqualTo("schema.table");
        assertThat(SqlSafe.qualifiedIdentifier("t.column_name")).isEqualTo("t.column_name");
    }

    @Test
    void qualifiedIdentifier_rejectsInjection() {
        assertThatThrownBy(() -> SqlSafe.qualifiedIdentifier("t.col; DROP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- operator ---

    @Test
    void operator_validOps() {
        assertThat(SqlSafe.operator("=")).isEqualTo("=");
        assertThat(SqlSafe.operator("!=")).isEqualTo("!=");
        assertThat(SqlSafe.operator("<>")).isEqualTo("<>");
        assertThat(SqlSafe.operator("<")).isEqualTo("<");
        assertThat(SqlSafe.operator(">")).isEqualTo(">");
        assertThat(SqlSafe.operator("<=")).isEqualTo("<=");
        assertThat(SqlSafe.operator(">=")).isEqualTo(">=");
        assertThat(SqlSafe.operator("LIKE")).isEqualTo("LIKE");
        assertThat(SqlSafe.operator("like")).isEqualTo("like");
        assertThat(SqlSafe.operator("IN")).isEqualTo("IN");
        assertThat(SqlSafe.operator("IS")).isEqualTo("IS");
        assertThat(SqlSafe.operator("IS NOT")).isEqualTo("IS NOT");
    }

    @Test
    void operator_rejectsNull() {
        assertThatThrownBy(() -> SqlSafe.operator(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operator_rejectsInjection() {
        assertThatThrownBy(() -> SqlSafe.operator("= ? OR 1=1 --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operator_rejectsArbitrary() {
        assertThatThrownBy(() -> SqlSafe.operator("DROP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operator_trimsPadding() {
        assertThat(SqlSafe.operator("  =  ")).isEqualTo("=");
    }

    // --- columns ---

    @Test
    void columns_star() {
        assertThat(SqlSafe.columns("*")).isEqualTo("*");
        assertThat(SqlSafe.columns(null)).isEqualTo("*");
    }

    @Test
    void columns_validList() {
        assertThat(SqlSafe.columns("name, age, status")).isEqualTo("name, age, status");
    }

    @Test
    void columns_rejectsInjection() {
        assertThatThrownBy(() -> SqlSafe.columns("name; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whereClause_acceptsParameterizedPredicates() {
        assertThat(SqlSafe.whereClause("active = ? AND name LIKE ?"))
                .isEqualTo("active = ? AND name LIKE ?");
        assertThat(SqlSafe.whereClause("deleted_at IS NULL")).isEqualTo("deleted_at IS NULL");
        assertThat(SqlSafe.whereClause("id IN (?, ?, ?)")) .isEqualTo("id IN (?, ?, ?)");
    }

    @Test
    void whereClause_rejectsLiteralsCommentsAndSubqueries() {
        assertThatThrownBy(() -> SqlSafe.whereClause("id = 1 OR 1 = 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafe.whereClause("id = ? --"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafe.whereClause("id IN (SELECT id FROM admin)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whereClause_rejectsOversizedAdversarialInputWithoutStackOverflow() {
        String adversarial = "a = ? AND ".repeat(500) + "x = ?";

        assertThatThrownBy(() -> SqlSafe.whereClause(adversarial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe SQL where clause");
    }

    @Test
    void whereClause_rejectsMalformedQualifiedIdentifiersAndInLists() {
        assertThatThrownBy(() -> SqlSafe.whereClause("user..id = ?"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafe.whereClause("id IN ?"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlSafe.whereClause("id IN (?,)"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
