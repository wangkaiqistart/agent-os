package com.agentos.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/** MessageStore 的共享最小契约：只追加新增消息并保持顺序。 */
public abstract class MessageStoreContractTest {
    protected abstract MessageStore messages();

    protected abstract SessionSnapshot session();

    protected abstract TurnSnapshot turn();

    @Test
    void appendsMessagesInOrder() {
        messages().append(turn(), 0, List.of(new UserMessage("one"), new UserMessage("two")));
        var history = messages().recentTurns(session(), 1).getFirst();
        assertThat(history.messages()).extracting(Message::getText).containsExactly("one", "two");
    }
}
