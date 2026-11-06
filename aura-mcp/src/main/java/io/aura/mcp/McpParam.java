package io.aura.mcp;

import io.aura.contract.ApiParameter;
import io.aura.contract.ContractException;

import java.util.Objects;

/**
 * One tool argument. {@link ApiParameter} is the whole of it: a tool parameter is a route
 * parameter, so an MCP argument has no metadata of its own to invent — not an enum label set,
 * not a Java-to-JSON type, not a required flag.
 */
public final class McpParam {
    private final ApiParameter contract;

    public McpParam(ApiParameter contract) {
        this.contract = Objects.requireNonNull(contract, "contract");
        // Every tool parameter in the system — a route contract or the HTTP bridge — passes
        // through here, so refusing to describe an undescribable type is stated once. Publishing
        // {"type":"string"} for a record or an enum tells the model to send a value the handler
        // can never receive.
        if (contract.schema().isEmpty() && !McpUtil.rendersWithoutSchema(contract.type())) {
            throw new ContractException("AURA_MCP_PARAM_SCHEMA_MISSING",
                    "MCP parameter '" + contract.name() + "' is declared as " + contract.type()
                            + ", which the Java-to-JSON table cannot render; declare its schema on the"
                            + " route (the route catalog does this for a body or enum parameter) or"
                            + " tools/list would describe it as a string.");
        }
    }

    public ApiParameter contract() { return contract; }
    public String name() { return contract.name(); }
    public String type() { return contract.type(); }
    public String description() { return contract.description(); }
    public boolean required() { return contract.required(); }
}
