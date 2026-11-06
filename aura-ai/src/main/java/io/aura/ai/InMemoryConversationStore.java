package io.aura.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded reference conversation store for tests, local prototypes, and deterministic examples. */
public final class InMemoryConversationStore implements ConversationStore {
    public static final int DEFAULT_MAX_CONVERSATIONS = 10_000;
    public static final int DEFAULT_MAX_ID_LENGTH = 256;

    private final int maxMessages;
    private final long maxCharacters;
    private final int maxConversations;
    private final int maxIdLength;
    private final Map<String, State> conversations = new ConcurrentHashMap<>();

    public InMemoryConversationStore(int maxMessages, long maxCharacters) {
        this(maxMessages, maxCharacters, DEFAULT_MAX_CONVERSATIONS, DEFAULT_MAX_ID_LENGTH);
    }

    public InMemoryConversationStore(int maxMessages, long maxCharacters,
                                     int maxConversations, int maxIdLength) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be positive");
        if (maxCharacters < 1) throw new IllegalArgumentException("maxCharacters must be positive");
        if (maxConversations < 1) throw new IllegalArgumentException("maxConversations must be positive");
        if (maxIdLength < 1) throw new IllegalArgumentException("maxIdLength must be positive");
        this.maxMessages = maxMessages;
        this.maxCharacters = maxCharacters;
        this.maxConversations = maxConversations;
        this.maxIdLength = maxIdLength;
    }

    public int maxMessages() { return maxMessages; }
    public long maxCharacters() { return maxCharacters; }
    public int maxConversations() { return maxConversations; }
    public int maxIdLength() { return maxIdLength; }

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
        State state = stateFor(id);
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

    /**
     * Creates the state for a new conversation while holding the map monitor so the
     * conversation-count bound cannot be exceeded by concurrent first appends.
     */
    private State stateFor(String id) {
        State existing = conversations.get(id);
        if (existing != null) return existing;
        synchronized (conversations) {
            existing = conversations.get(id);
            if (existing != null) return existing;
            if (conversations.size() >= maxConversations) {
                throw new IllegalStateException("conversation store is full: " + maxConversations);
            }
            State created = new State();
            conversations.put(id, created);
            return created;
        }
    }

    private String requireId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        if (value.length() > maxIdLength) {
            throw new IllegalArgumentException("conversationId exceeds maxIdLength: " + maxIdLength);
        }
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
