package io.aura.ai;

import java.util.List;

/** Provider-neutral conversation persistence extension point. */
public interface ConversationStore {
    void append(String conversationId, AiMessage message);
    List<AiMessage> load(String conversationId);

    record AiMessage(String role, String content) {
        public AiMessage {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
            if (content == null) throw new IllegalArgumentException("content must not be null");
        }
    }
}
