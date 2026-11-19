package com.agentos.core.interaction;

/** 终态结果；没有合法回答的状态不携带正文。 */
public record InteractionResult(Status status, String answer) {
    public enum Status {
        ANSWERED,
        UNSUPPORTED,
        EOF,
        BUSY,
        TOO_LONG,
        TIMED_OUT,
        CANCELLED
    }

    public InteractionResult {
        java.util.Objects.requireNonNull(status);
        if (status == Status.ANSWERED) {
            if (answer == null || answer.length() > 4000) {
                throw new IllegalArgumentException("TOO_LONG");
            }
        } else if (answer != null) {
            throw new IllegalArgumentException("非回答终态不得携带正文");
        }
    }

    public static InteractionResult of(Status status) {
        return new InteractionResult(status, null);
    }
}
