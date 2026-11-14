package com.agentos.core.provider;

import java.util.function.Consumer;

/** 对一次聊天模型调用负责，不驱动 ReAct 循环、不执行工具、不保存记忆。 */
public interface ProviderService {

    /** 同步调用，返回模型原始响应与本次所有尝试记录。 */
    ProviderResult call(ProviderRequest request);

    /** 流式调用，按顺序交付文本增量，返回聚合后的完整响应。 */
    ProviderResult stream(ProviderRequest request, Consumer<String> textSink);
}
