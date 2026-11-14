package com.agentos.provider.chat;

import com.agentos.provider.config.ProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按单个连接配置编程式构建 {@link OpenAiChatModel}。
 *
 * <p>只由 ProviderService 内部使用，不注册为 ChatModel Bean，避免靠扫描容器区分 Provider。
 * 连接级选项只放连接参数（地址、密钥、超时、关闭 SDK 重试），model、温度、输出上限等生成参数
 * 每次请求由 Profile 写进 Prompt 的选项。
 */
public final class ChatModelFactory {

    /** 容器注入的官方 Manager：模型实现用它解析工具定义，不能自建。 */
    private final ToolCallingManager toolCallingManager;

    /** 容器注入的观测注册表，让每次模型调用产生 gen_ai.client.operation 观测。 */
    private final ObservationRegistry observationRegistry;

    public ChatModelFactory(
            ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry) {
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 构建连接对应的模型对象。只创建 SDK 客户端，不发任何网络请求。
     *
     * <p>Spring AI 2.0.2 的 {@code toolCallingManager(...)} 已标记为待移除，但这是该版本 Builder 暴露的
     * 唯一注入入口。省略它时，{@code build()} 会内部创建新的 {@code DefaultToolCallingManager}，绕过 Spring
     * 容器中的限流、异常处理和观测配置。因此当前版本必须继续调用，并把弃用压制限制在这个方法；升级 Spring AI
     * 后应优先改用官方替代 API，并保留下面的回归测试。
     *
     * @param connection 已绑定的连接配置
     * @return 未注册为 Bean 的模型对象
     */
    @SuppressWarnings("deprecation")
    public OpenAiChatModel create(ProviderProperties.Connection connection) {
        OpenAiChatOptions connectionOptions =
                OpenAiChatOptions.builder()
                        .baseUrl(connection.getBaseUrl())
                        .apiKey(connection.getApiKey())
                        .timeout(connection.getTimeout())
                        // 重试由 ProviderService 统一控制，SDK 内置重试必须关掉。
                        .maxRetries(0)
                        .build();
        return OpenAiChatModel.builder()
                .options(connectionOptions)
                // 2.0.2 唯一可用的注入入口；不能删，否则 build() 会自建 DefaultToolCallingManager。
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry)
                .build();
    }
}
