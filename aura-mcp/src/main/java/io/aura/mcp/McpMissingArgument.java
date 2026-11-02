package io.aura.mcp;

/**
 * A required tool argument the model did not send.
 *
 * <p>Both MCP doors answer with {@code MCP_MISSING_ARGUMENT}: the bridge refuses to build an HTTP
 * request it knows the server will reject, and the router refuses to run a tool whose own
 * {@code tools/list} entry declared the argument required. One type keeps the two doors from
 * inventing two names for one fact.
 */
final class McpMissingArgument extends IllegalArgumentException {

    private final String name;

    McpMissingArgument(String name) {
        super("required argument missing: " + name);
        this.name = name;
    }

    String name() { return name; }
}
