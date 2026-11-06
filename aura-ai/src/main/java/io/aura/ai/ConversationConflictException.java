package io.aura.ai;

/** Stable optimistic-concurrency failure for conversation appends. */
public final class ConversationConflictException extends IllegalStateException {
    private final String conversationId;
    private final long expectedVersion;
    private final long actualVersion;

    public ConversationConflictException(String conversationId, long expectedVersion, long actualVersion) {
        super("conversation version conflict");
        this.conversationId = conversationId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String conversationId() { return conversationId; }
    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
