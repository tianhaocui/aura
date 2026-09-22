package io.aura.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded reference conversation store for tests, local prototypes, and deterministic examples. */
public final class InMemoryConversationStore implements ConversationStore {
    private final int maxMessages;
    private final long maxCharacters;
    private final Map<String, State> conversations = new ConcurrentHashMap<>();

    public InMemoryConversationStore(int maxMessages, long maxCharacters) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be positive");
        if (maxCharacters < 1) throw new IllegalArgumentException("maxCharacters must be positive");
        this.maxMessages = maxMessages;
        this.maxCharacters = maxCharacters;
    }

    public int maxMessages() { return maxMessages; }
    public long maxCharacters() { return maxCharacters; }

    @Override public ConversationSnapshot load(String conversationId) {
        String id = requireId(conversationId);
        State state = conversations.get(id);
        return state == null ? new ConversationSnapshot(id, 0, java.util.List.of(), 0)
                : state.snapshot(id);
    }

    @Override public ConversationSnapshot append(String conversationId, AiMessage message,
                                                 long expectedVersion) {
        String id = requireId(conversationId);
        Objects.requireNonNull(message, "message");
        if (message.characterCount() > maxCharacters) {
            throw new IllegalArgumentException("message exceeds maxCharacters");
        }
        State state = conversations.computeIfAbsent(id, ignored -> new State());
        synchronized (state) {
            if (expectedVersion >= 0 && expectedVersion != state.version) {
                throw new ConversationConflictException(id, expectedVersion, state.version);
            }
            state.messages.addLast(message);
            state.characters += message.characterCount();
            while (state.messages.size() > maxMessages || state.characters > maxCharacters) {
                state.characters -= state.messages.removeFirst().characterCount();
            }
            state.version++;
            return state.snapshot(id);
        }
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        return value;
    }

    private static final class State {
        private final Deque<AiMessage> messages = new ArrayDeque<>();
        private long version;
        private long characters;

        private ConversationSnapshot snapshot(String id) {
            return new ConversationSnapshot(id, version, new ArrayList<>(messages), characters);
        }
    }
}
