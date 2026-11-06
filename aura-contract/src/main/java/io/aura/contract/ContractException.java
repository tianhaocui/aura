package io.aura.contract;

/** Runtime exception carrying an adapter-independent structured error. */
public class ContractException extends RuntimeException {
    private final StructuredError error;

    public ContractException(StructuredError error) {
        super(error == null ? null : error.message());
        this.error = java.util.Objects.requireNonNull(error, "error");
    }

    public ContractException(String code, String message) {
        this(new StructuredError(code, message));
    }

    public StructuredError error() {
        return error;
    }
}
