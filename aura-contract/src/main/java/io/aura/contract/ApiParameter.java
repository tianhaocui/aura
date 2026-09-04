package io.aura.contract;

/** Stable AI-facing description of one API parameter. */
public record ApiParameter(String name, String type, String source, boolean required, String description) {
    public ApiParameter {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("parameter name must not be blank");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("parameter type must not be blank");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("parameter source must not be blank");
        name = name.trim();
        type = type.trim();
        source = source.trim();
    }
}
