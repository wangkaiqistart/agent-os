package com.agentos.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * 一次待授权动作，不在构造时执行外部操作。
 *
 * @param type 读文件、写文件、Shell、HTTP 等类别
 * @param target 该动作的目标描述，具体解析与白名单校验交给沙箱实现
 * @param details 目标之外的补充信息（如命令参数、工作目录、HTTP 方法），只用于判定与向用户展示
 */
public record SandboxAction(ActionType type, String target, List<String> details) {
    public SandboxAction(ActionType type, String target) {
        this(type, target, List.of());
    }

    public SandboxAction {
        type = Objects.requireNonNull(type, "type");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("sandbox target 不能为空");
        }
        details = details == null ? List.of() : List.copyOf(details);
    }
}
