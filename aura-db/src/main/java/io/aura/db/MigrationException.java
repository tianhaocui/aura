package io.aura.db;

public class MigrationException extends RuntimeException implements io.aura.contract.CodedFailure {
    public static final String CODE = "DB_MIGRATION_FAILED";

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() { return CODE; }
}
