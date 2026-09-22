package io.aura.ai;

import java.util.List;
import java.util.Objects;

/** Provider-neutral, versioned conversation persistence boundary. */
public interface ConversationStore {
    /** Returns an immutable snapshot; an unknown conversation returns version zero and no messages. */
    ConversationSnapshot load(String conversationId);

    /** Appends atomically; expectedVersion -1 means unconditional append. */
    ConversationSnapshot append(String conversationId, AiMessage message, long expectedVersion);

    default ConversationSnapshot append(String conversationId, AiMessage message) {
        return append(conversationId, message, -1);
    }

    record ConversationSnapshot(String conversationId, long version, List<AiMessage> messages,
                                long characterCount) {
        public ConversationSnapshot {
            if (conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException("conversationId must not be blank");
            }
            if (version < 0 || characterCount < 0) {
                throw new IllegalArgumentException("conversation snapshot counters must not be negative");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    record AiMessage(String role, String content) {
        public AiMessage {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
            if (content == null) throw new IllegalArgumentException("content must not be null");
        }

        public long characterCount() { return (long) role.length() + content.length(); }
    }
}
