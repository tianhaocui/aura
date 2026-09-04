package io.aura;

/** Structured startup failure for module graph and lifecycle errors. */
public class ModuleException extends IllegalStateException {
    private final String code;
    private final String module;

    public ModuleException(String code, String module, String message) {
        super(message);
        this.code = code;
        this.module = module;
    }

    public String code() { return code; }
    public String module() { return module; }
}
