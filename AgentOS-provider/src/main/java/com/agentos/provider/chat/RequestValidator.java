package com.agentos.provider.chat;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.Profile;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 外呼前校验：把 Profile 解析成"具体连接 + 模型名"。
 *
 * <p>所有失败都在发出网络请求之前抛 {@link AgentOSException}（INVALID_ARGUMENT），
 * 不会静默改用其他连接或模型（FR-007）。连接名不区分大小写，按显式映射查找，
 * 不根据连接类型或注册顺序推断（FR-004）。
 */
public final class RequestValidator {

    /** 小写连接名到连接的索引；构造后不再修改。 */
    private final Map<String, ProviderConnection> byLowerName;

    private final String defaultProvider;

    /**
     * @param connections 启动时建好的连接，key 为连接名
     * @param defaultProvider Profile 没写 provider 时使用的连接名
     */
    public RequestValidator(Map<String, ProviderConnection> connections, String defaultProvider) {
        Objects.requireNonNull(connections, "connections");
        this.defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider");
        Map<String, ProviderConnection> index = new HashMap<>();
        // Locale.ROOT：避免土耳其语等区域设置下 "I" 的小写不是 "i"。
        connections.forEach((name, connection) -> index.put(lower(name), connection));
        this.byLowerName = Map.copyOf(index);
    }

    /**
     * 解析本次调用要用的连接和模型。
     *
     * @throws AgentOSException 连接未知、不可用，或 Profile 缺少聊天模型
     */
    public Resolved resolve(Profile profile) {
        String requested = hasText(profile.provider()) ? profile.provider() : defaultProvider;
        ProviderConnection connection = byLowerName.get(lower(requested));
        if (connection == null) {
            // 消息里写调用方给的原始名称，方便对照 Profile 排查。
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "未知的 provider：" + requested);
        }
        if (!connection.available()) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, unavailableMessage(connection));
        }
        if (!hasText(profile.model())) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "Profile 缺少聊天模型");
        }
        return new Resolved(connection, profile.model());
    }

    private static String unavailableMessage(ProviderConnection connection) {
        if (connection.envVarName() == null) {
            // 密钥不是用 ${ENV_VAR} 写的，没有变量名可提示。
            return "provider 不可用：" + connection.name() + "，缺少 API key";
        }
        return "provider 不可用：" + connection.name() + "，缺少环境变量 " + connection.envVarName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 校验通过后的解析结果。
     *
     * @param connection 本次调用使用的、可用的连接
     * @param model 已确认非空白的聊天模型名
     */
    public record Resolved(ProviderConnection connection, String model) {}
}
