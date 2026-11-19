package com.agentos.core.interaction;

/** 每轮入口能力，旧调用默认无人值守。 */
public record TurnRuntimeCapabilities(InteractionPort interaction) {
    public static final TurnRuntimeCapabilities NONE =
            new TurnRuntimeCapabilities(InteractionPort.UNSUPPORTED);

    public TurnRuntimeCapabilities {
        java.util.Objects.requireNonNull(interaction);
    }
}
