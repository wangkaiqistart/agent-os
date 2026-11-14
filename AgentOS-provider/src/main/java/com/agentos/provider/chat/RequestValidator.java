package com.agentos.provider.chat;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.Profile;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;

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

    /**
     * 检查消息里的媒体并统计图片数量（FR-036、FR-037）。
     *
     * <p>只有用户消息可以带图片，且只接受 {@code image/*}。音频、视频、文件等其他媒体，以及非用户消息携带的
     * 任何媒体，都在外呼前失败：不静默丢弃，也不原样转发。错误信息只写媒体类型，不写数据和网址。
     *
     * @return 全部消息里的图片数量，供调用记录使用
     * @throws AgentOSException 出现不支持的媒体
     */
    public static int countImages(List<Message> messages) {
        int images = 0;
        for (Message message : messages) {
            if (!(message instanceof MediaContent content) || content.getMedia().isEmpty()) {
                continue;
            }
            if (!(message instanceof UserMessage)) {
                throw new AgentOSException(
                        ErrorCode.INVALID_ARGUMENT, "只有用户消息可以携带图片，当前是 " + message.getMessageType());
            }
            for (Media media : content.getMedia()) {
                // 不用 MimeType.toString()：它会带上 charset 等参数，错误信息只要类型本身。
                String type = media.getMimeType().getType();
                if (!"image".equals(type)) {
                    throw new AgentOSException(
                            ErrorCode.INVALID_ARGUMENT,
                            "不支持的媒体类型：" + type + "/" + media.getMimeType().getSubtype());
                }
                images++;
            }
        }
        return images;
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
