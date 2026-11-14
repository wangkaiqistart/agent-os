package com.agentos.core.provider;

import java.util.function.Consumer;

/**
 * 对一次聊天模型调用负责，不驱动 ReAct 循环、不执行工具、不保存记忆。
 *
 * <p>它是 core 层定义的接口。调用方只依赖这个抽象，不需要知道底层是 OpenAI、DeepSeek 还是测试替身。
 */
public interface ProviderService {

    /** 同步调用，返回模型原始响应与本次所有尝试记录。 */
    ProviderResult call(ProviderRequest request);

    /**
     * 流式调用，按顺序交付文本增量，返回聚合后的完整响应。
     *
     * <p>{@code textSink} 是“收到一小段文本后交给谁”的回调，ProviderService 不需要知道它最终写入 HTTP、CLI 还是日志。
     */
    ProviderResult stream(ProviderRequest request, Consumer<String> textSink);
}
