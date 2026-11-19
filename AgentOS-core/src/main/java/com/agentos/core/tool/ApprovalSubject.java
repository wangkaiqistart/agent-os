package com.agentos.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * 需要用户确认的动作摘要，由沙箱生成、由入口展示。
 *
 * @param type 动作类别
 * @param title 一句话说明，例如"执行命令"
 * @param lines 展示给用户的"名称 值"行，值已是安全表示（相对路径、脱敏 URL）
 * @param grantKey 会话级授权的匹配键；同一会话内相同键不再询问
 * @param grantLabel 向用户说明"本会话不再询问"覆盖的范围
 * @param sessionGrantAllowed false 时只允许"本次"，用于修改 Agent 定义等敏感目标
 */
public record ApprovalSubject(
        ActionType type,
        String title,
        List<String> lines,
        String grantKey,
        String grantLabel,
        boolean sessionGrantAllowed) {
    public ApprovalSubject {
        Objects.requireNonNull(type, "type");
        if (title == null || title.isBlank() || grantKey == null || grantKey.isBlank()) {
            throw new IllegalArgumentException("授权摘要不完整");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        grantLabel = grantLabel == null ? grantKey : grantLabel;
    }
}
