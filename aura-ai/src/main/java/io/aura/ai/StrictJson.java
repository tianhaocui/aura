package io.aura.ai;

/** Small RFC 8259 syntax validator used before Fastjson schema evaluation. */
final class StrictJson {
    private static final int MAX_DEPTH = 128;
    private final String value;
    private int offset;

    private StrictJson(String value) { this.value = value; }

    static void validate(String value) {
        StrictJson parser = new StrictJson(value);
        parser.whitespace();
        parser.value(0);
        parser.whitespace();
        if (parser.offset != value.length()) parser.fail();
    }

    private void value(int depth) {
        if (depth > MAX_DEPTH || offset >= value.length()) fail();
        switch (value.charAt(offset)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        }
    }

    private void object(int depth) {
        offset++;
        whitespace();
        if (take('}')) return;
        while (true) {
            if (offset >= value.length() || value.charAt(offset) != '"') fail();
            string();
            whitespace();
            require(':');
            whitespace();
            value(depth);
            whitespace();
            if (take('}')) return;
            require(',');
            whitespace();
        }
    }

    private void array(int depth) {
        offset++;
        whitespace();
        if (take(']')) return;
        while (true) {
            value(depth);
            whitespace();
            if (take(']')) return;
            require(',');
            whitespace();
        }
    }

    private void string() {
        require('"');
        while (offset < value.length()) {
            char current = value.charAt(offset++);
            if (current == '"') return;
            if (current < 0x20) fail();
            if (current != '\\') continue;
            if (offset >= value.length()) fail();
            char escape = value.charAt(offset++);
            if ("\"\\/bfnrt".indexOf(escape) >= 0) continue;
            if (escape != 'u' || offset + 4 > value.length()) fail();
            for (int i = 0; i < 4; i++) {
                if (Character.digit(value.charAt(offset++), 16) < 0) fail();
            }
        }
        fail();
    }

    private void number() {
        if (take('-') && offset >= value.length()) fail();
        if (take('0')) {
            if (offset < value.length() && Character.isDigit(value.charAt(offset))) fail();
        } else {
            digits();
        }
        if (take('.')) digits();
        if (offset < value.length() && (value.charAt(offset) == 'e' || value.charAt(offset) == 'E')) {
            offset++;
            if (offset < value.length() && (value.charAt(offset) == '+' || value.charAt(offset) == '-')) offset++;
            digits();
        }
    }

    private void digits() {
        int start = offset;
        while (offset < value.length() && Character.isDigit(value.charAt(offset))) offset++;
        if (offset == start) fail();
    }

    private void literal(String literal) {
        if (!value.regionMatches(offset, literal, 0, literal.length())) fail();
        offset += literal.length();
    }

    private void whitespace() {
        while (offset < value.length()) {
            char current = value.charAt(offset);
            if (current != ' ' && current != '\n' && current != '\r' && current != '\t') return;
            offset++;
        }
    }

    private boolean take(char expected) {
        if (offset < value.length() && value.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void require(char expected) {
        if (!take(expected)) fail();
    }

    private void fail() { throw new IllegalArgumentException("invalid RFC 8259 JSON at offset " + offset); }
}
