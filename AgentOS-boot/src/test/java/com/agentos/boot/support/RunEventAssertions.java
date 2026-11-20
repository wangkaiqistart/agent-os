package com.agentos.boot.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnEventPayload;
import java.util.List;

/**
 * 按 specs/007-run-event-stream/contracts/run-events.md §2 的顺序语法校验事件序列。
 *
 * <p>boot 测试拿不到 core 模块的测试类，这里按同一套规则另写一份，两者保持一致。
 */
public final class RunEventAssertions {
    private RunEventAssertions() {}

    /** 完整序列：首条轮次开始、末条唯一终态，主体满足 {@link #assertBodyWellFormed}。 */
    public static void assertWellFormed(List<TurnEvent> events) {
        assertThat(events).as("事件序列").isNotEmpty();
        assertThat(events.getFirst().payload()).isInstanceOf(TurnEventPayload.RunStarted.class);
        assertThat(events.getLast().payload().terminal()).as("末条为终态").isTrue();
        assertThat(events.stream().filter(e -> e.payload().terminal()).count())
                .as("终态只有一条")
                .isOne();
        assertThat(events.get(0).seq()).isOne();
        assertBodyWellFormed(events.subList(1, events.size() - 1), 2);
    }

    /** 只校验步骤、消息、工具、交互事件的成对与顺序以及 seq 连续；直接测试循环时使用。 */
    public static void assertBodyWellFormed(List<TurnEvent> events) {
        assertBodyWellFormed(events, events.isEmpty() ? 1 : events.getFirst().seq());
    }

    private static void assertBodyWellFormed(List<TurnEvent> events, long firstSeq) {
        long expected = firstSeq;
        Integer step = null;
        String message = null;
        String tool = null;
        String interaction = null;
        for (TurnEvent event : events) {
            assertThat(event.seq()).as("seq 连续").isEqualTo(expected++);
            switch (event.payload()) {
                case TurnEventPayload.StepStarted started -> {
                    assertThat(step).as("步骤不能嵌套").isNull();
                    assertThat(tool).as("工具未结束时不能开始新步骤").isNull();
                    step = started.iteration();
                }
                case TurnEventPayload.StepFinished finished -> {
                    assertThat(step).as("步骤结束对应开始").isEqualTo(finished.iteration());
                    assertThat(message).as("消息先于步骤结束关闭").isNull();
                    step = null;
                }
                case TurnEventPayload.MessageStarted started -> {
                    assertThat(step).as("消息位于步骤内").isNotNull();
                    assertThat(message).isNull();
                    message = started.messageId();
                }
                case TurnEventPayload.MessageDelta delta ->
                        assertThat(delta.messageId()).isEqualTo(message);
                case TurnEventPayload.MessageEnded ended -> {
                    assertThat(ended.messageId()).isEqualTo(message);
                    message = null;
                }
                case TurnEventPayload.ToolStarted started -> {
                    assertThat(step).as("工具位于步骤结束之后").isNull();
                    assertThat(tool).as("工具不能嵌套").isNull();
                    tool = started.toolCallId();
                }
                case TurnEventPayload.ToolFinished finished -> {
                    assertThat(finished.toolCallId()).isEqualTo(tool);
                    assertThat(interaction).as("交互先于工具结束关闭").isNull();
                    tool = null;
                }
                case TurnEventPayload.ApprovalRequested requested -> {
                    assertThat(requested.toolCallId()).as("确认位于所属工具内").isEqualTo(tool);
                    interaction = requested.requestId().toString();
                }
                case TurnEventPayload.ApprovalResolved resolved -> {
                    assertThat(resolved.requestId().toString()).isEqualTo(interaction);
                    interaction = null;
                }
                case TurnEventPayload.InputRequested requested -> {
                    assertThat(requested.toolCallId()).as("提问位于所属工具内").isEqualTo(tool);
                    interaction = requested.requestId().toString();
                }
                case TurnEventPayload.InputResolved resolved -> {
                    assertThat(resolved.requestId().toString()).isEqualTo(interaction);
                    interaction = null;
                }
                default ->
                        assertThat(
                                        event.payload().terminal()
                                                || event.payload()
                                                        instanceof TurnEventPayload.RunStarted)
                                .as("主体里不应出现轮次开始或终态")
                                .isFalse();
            }
        }
    }
}
