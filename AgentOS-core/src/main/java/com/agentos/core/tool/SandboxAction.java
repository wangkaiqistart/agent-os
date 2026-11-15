package com.agentos.core.tool;

import java.util.Objects;

public record SandboxAction(ActionType type, String target) {
    public SandboxAction {
        type = Objects.requireNonNull(type, "type");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("sandbox target 不能为空");
        }
    }
}
