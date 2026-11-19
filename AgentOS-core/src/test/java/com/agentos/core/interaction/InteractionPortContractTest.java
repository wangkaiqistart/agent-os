package com.agentos.core.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InteractionPortContractTest {
    @Test
    void unsupportedIsImmediateAndIdentityAndBoundsAreExplicit() {
        var turn = java.util.UUID.randomUUID();
        var id = java.util.UUID.randomUUID();
        var request =
                new InteractionRequest(id, turn, "call-1", "问题", System.nanoTime() + 1000000000L);
        assertThat(request.turnId()).isEqualTo(turn);
        assertThat(request.requestId()).isEqualTo(id);
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            assertThat(TurnRuntimeCapabilities.NONE.interaction().ask(request, null).status())
                    .isEqualTo(InteractionResult.Status.UNSUPPORTED);
            assertThat(System.nanoTime() - start).isLessThan(1000000000L);
        }
        assertThatThrownBy(() -> new InteractionRequest(id, turn, "call-1", "x".repeat(4001), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new InteractionResult(InteractionResult.Status.ANSWERED, "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InteractionResult(InteractionResult.Status.CANCELLED, "late"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
