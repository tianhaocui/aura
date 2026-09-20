package io.aura;

/** Structured startup failure for module graph and lifecycle errors. */
public class ModuleException extends IllegalStateException implements io.aura.contract.CodedFailure {
    private final String code;
    private final String module;
    private final String causeCode;

    public ModuleException(String code, String module, String message) {
        this(code, module, message, null);
    }

    public ModuleException(String code, String module, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.module = module;
        this.causeCode = cause instanceof io.aura.contract.CodedFailure coded
                ? coded.code() : null;
    }

    public String code() { return code; }
    public String module() { return module; }
    public String causeCode() { return causeCode; }
}
