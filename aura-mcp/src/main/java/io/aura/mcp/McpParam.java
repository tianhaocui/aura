package io.aura.mcp;

import io.aura.contract.ApiParameter;

import java.util.List;

/** MCP-specific enum metadata attached to the shared API parameter contract. */
public final class McpParam {
    private final ApiParameter contract;
    private final List<McpEnumValue> enumValues;

    public McpParam(String name, String type, String description, List<McpEnumValue> enumValues) {
        this(name, type, description, enumValues, true);
    }

    public McpParam(String name, String type, String description,
                    List<McpEnumValue> enumValues, boolean required) {
        this(new ApiParameter(name, type, "tool", required, description), enumValues);
    }

    public McpParam(ApiParameter contract, List<McpEnumValue> enumValues) {
        this.contract = java.util.Objects.requireNonNull(contract, "contract");
        if (!"tool".equals(contract.source())) {
            throw new IllegalArgumentException("MCP parameters must use source 'tool'");
        }
        this.enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    public ApiParameter contract() { return contract; }
    public String name() { return contract.name(); }
    public String type() { return contract.type(); }
    public String description() { return contract.description(); }
    public boolean required() { return contract.required(); }
    public List<McpEnumValue> enumValues() { return enumValues; }
    public boolean isEnum() { return !enumValues.isEmpty(); }
}
