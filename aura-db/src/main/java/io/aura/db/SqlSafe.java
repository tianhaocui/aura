package io.aura.db;

import java.util.Set;
import java.util.regex.Pattern;

final class SqlSafe {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.]*");
    private static final int MAX_WHERE_LENGTH = 4096;
    private static final Set<String> VALID_OPS = Set.of(
            "=", "!=", "<>", "<", ">", "<=", ">=",
            "LIKE", "like", "NOT LIKE", "not like",
            "IN", "in", "NOT IN", "not in",
            "IS", "is", "IS NOT", "is not"
    );

    static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }

    static String qualifiedIdentifier(String name) {
        if (name == null || !QUALIFIED_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }

    static String operator(String op) {
        if (op == null || !VALID_OPS.contains(op.trim())) {
            throw new IllegalArgumentException("Invalid SQL operator: " + op);
        }
        return op.trim();
    }

    static String columns(String cols) {
        if (cols == null) return "*";
        if ("*".equals(cols.trim())) return "*";
        for (String part : cols.split(",")) {
            String col = part.trim();
            if (!col.isEmpty() && !IDENTIFIER.matcher(col).matches()) {
                throw new IllegalArgumentException("Invalid column name: " + col);
            }
        }
        return cols;
    }

    /**
     * Validates the legacy where-fragment API. Only parameterized predicates
     * over identifiers are accepted; literals, comments, subqueries and
     * statement separators are intentionally rejected.
     */
    static String whereClause(String where) {
        if (where == null || where.isBlank() || where.length() > MAX_WHERE_LENGTH) {
            throw new IllegalArgumentException("Unsafe SQL where clause; use parameterized predicates only");
        }
        String trimmed = where.trim();
        if (!new WhereParser(trimmed).parse()) {
            throw new IllegalArgumentException("Unsafe SQL where clause; use parameterized predicates only");
        }
        return trimmed;
    }

    /** Linear parser for the deliberately small legacy WHERE-fragment grammar. */
    private static final class WhereParser {
        private final String input;
        private int index;

        private WhereParser(String input) { this.input = input; }

        boolean parse() {
            if (!predicate()) return false;
            while (true) {
                skipSpace();
                if (index == input.length()) return true;
                if (!(word("AND") || word("OR")) || !predicate()) return false;
            }
        }

        private boolean predicate() {
            if (!identifier()) return false;
            if (word("IS")) {
                word("NOT");
                return word("NULL");
            }

            boolean negated = word("NOT");
            if (word("LIKE")) return question();
            if (word("IN")) return placeholders();
            if (negated) return false;
            return comparisonOperator() && question();
        }

        private boolean placeholders() {
            if (!symbol('(') || !question()) return false;
            while (symbol(',')) {
                if (!question()) return false;
            }
            return symbol(')');
        }

        private boolean comparisonOperator() {
            skipSpace();
            if (index >= input.length()) return false;
            char first = input.charAt(index);
            if (first == '=') { index++; return true; }
            if (first != '!' && first != '<' && first != '>') return false;
            index++;
            if (index < input.length() && (input.charAt(index) == '='
                    || first == '<' && input.charAt(index) == '>')) {
                index++;
                return true;
            }
            return first != '!';
        }

        private boolean identifier() {
            skipSpace();
            if (!identifierPart()) return false;
            while (index < input.length() && input.charAt(index) == '.') {
                index++;
                if (!identifierPart()) return false;
            }
            return true;
        }

        private boolean identifierPart() {
            if (index >= input.length() || !isIdentifierStart(input.charAt(index))) return false;
            index++;
            while (index < input.length() && isIdentifierPart(input.charAt(index))) index++;
            return true;
        }

        private boolean word(String expected) {
            skipSpace();
            int end = index + expected.length();
            if (end > input.length() || !input.regionMatches(true, index, expected, 0, expected.length())) return false;
            if (end < input.length() && isIdentifierPart(input.charAt(end))) return false;
            index = end;
            return true;
        }

        private boolean question() { return symbol('?'); }

        private boolean symbol(char expected) {
            skipSpace();
            if (index >= input.length() || input.charAt(index) != expected) return false;
            index++;
            return true;
        }

        private void skipSpace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        }

        private static boolean isIdentifierStart(char value) {
            return value == '_' || value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
        }

        private static boolean isIdentifierPart(char value) {
            return isIdentifierStart(value) || value >= '0' && value <= '9';
        }
    }

    private SqlSafe() {}
}
