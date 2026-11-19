package com.agentos.core.interaction;

/**
 * 用户对一次授权请求的决定。
 *
 * @param feedback 仅 DENY 可携带，用户希望 Agent 改用的做法，最多 {@link #MAX_FEEDBACK_CHARS} 字符
 */
public record ApprovalDecision(Outcome outcome, String feedback) {
    public static final int MAX_FEEDBACK_CHARS = 1000;

    public enum Outcome {
        ALLOW_ONCE,
        ALLOW_SESSION,
        DENY,
        UNSUPPORTED,
        TIMED_OUT,
        CANCELLED,
        EOF
    }

    public ApprovalDecision {
        java.util.Objects.requireNonNull(outcome);
        if (feedback != null && feedback.isBlank()) {
            feedback = null;
        }
        if (feedback != null && outcome != Outcome.DENY) {
            throw new IllegalArgumentException("只有拒绝可以附带说明");
        }
        if (feedback != null && feedback.length() > MAX_FEEDBACK_CHARS) {
            feedback = feedback.substring(0, MAX_FEEDBACK_CHARS);
        }
    }

    public static ApprovalDecision of(Outcome outcome) {
        return new ApprovalDecision(outcome, null);
    }

    public static ApprovalDecision deny(String feedback) {
        return new ApprovalDecision(Outcome.DENY, feedback);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW_ONCE || outcome == Outcome.ALLOW_SESSION;
    }
}
