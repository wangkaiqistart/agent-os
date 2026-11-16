package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

class ToolCallLedgerTest {
    private ToolCall call(String id, String name) {
        return new ToolCall(id, "function", name, "{}");
    }

    @Test
    void takesSameNameInResponseOrderAndLeavesOtherNamesInOrder() {
        var a = call("id1", "a");
        var b = call("id2", "b");
        var c = call("id3", "a");
        var checkpoints = new AtomicInteger();
        var ledger = new ToolCallLedger(List.of(a, b, c), checkpoints::incrementAndGet);
        assertThat(ledger.next("a")).isEqualTo(a);
        assertThat(ledger.next("a")).isEqualTo(c);
        assertThat(ledger.pending()).containsExactly(b);
        ledger.checkpoint();
        assertThat(checkpoints).hasValue(1);
        assertThatThrownBy(() -> ledger.next("a")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidIdsRejectWholeBatchWithoutGuessingIdsOrCallingCheckpoint() {
        for (var calls :
                List.of(
                        List.of(call("", "a")),
                        List.of(call("same", "a"), call("same", "b")),
                        List.of(call(" ", "a")))) {
            assertThatThrownBy(() ->
                            new ToolCallLedger(
                                    calls,
                                    () -> {
                                        throw new AssertionError("不能执行");
                                    }))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("ID");
        }
    }
}
