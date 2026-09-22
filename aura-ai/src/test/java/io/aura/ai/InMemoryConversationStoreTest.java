package io.aura.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryConversationStoreTest {
    @Test
    void keepsLatestMessagesInsideBothBoundsAndVersionsEveryAppend() {
        InMemoryConversationStore store = new InMemoryConversationStore(2, 24);
        var first = store.append("orders", new ConversationStore.AiMessage("user", "one"));
        var second = store.append("orders", new ConversationStore.AiMessage("assistant", "two"), first.version());
        var third = store.append("orders", new ConversationStore.AiMessage("user", "three"), second.version());

        assertThat(first.version()).isEqualTo(1);
        assertThat(third.version()).isEqualTo(3);
        assertThat(third.messages()).extracting(ConversationStore.AiMessage::content)
                .containsExactly("two", "three");
        assertThat(third.characterCount()).isEqualTo((long) "assistant".length() + 3
                + "user".length() + 5);
    }

    @Test
    void rejectsStaleVersionsAndOversizedSingleMessages() {
        InMemoryConversationStore store = new InMemoryConversationStore(4, 30);
        var snapshot = store.append("orders", new ConversationStore.AiMessage("user", "one"));

        assertThatThrownBy(() -> store.append("orders",
                new ConversationStore.AiMessage("assistant", "two"), snapshot.version() - 1))
                .isInstanceOf(ConversationConflictException.class)
                .hasMessage("conversation version conflict");
        assertThatThrownBy(() -> store.append("orders",
                new ConversationStore.AiMessage("user", "123456789012345678901234567890")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCharacters");
    }

    @Test
    void unknownConversationIsAnEmptyVersionZeroSnapshot() {
        var snapshot = new InMemoryConversationStore(1, 100).load("new");
        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.messages()).isEmpty();
        assertThat(snapshot.characterCount()).isZero();
    }
}
