package io.aura.mcp;

import java.util.List;
import java.util.Objects;

public record McpPrompt(String name, String description, String template, List<String> arguments) {

    public McpPrompt {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(template, "template");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
