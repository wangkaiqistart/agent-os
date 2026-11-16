package com.agentos.core.tool;

import java.util.Objects;

/**
 * 一次待授权动作，不在构造时执行外部操作。
 *
 * @param type 读文件、写文件、Shell、HTTP 等类别
 * @param target 该动作的目标描述，具体解析与白名单校验交给沙箱实现
 */
public record SandboxAction(ActionType type, String target) {
    public SandboxAction {
        type = Objects.requireNonNull(type, "type");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("sandbox target 不能为空");
        }
    }
}
