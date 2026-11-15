package com.agentos.core.support;

import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatResponse;

/** 测试专用脚本 Provider，不连接网络也不承载生产逻辑。 */
public final class ScriptedProviderService implements ProviderService {
    private final Queue<ChatResponse> responses = new ArrayDeque<>();
    private int calls;

    public ScriptedProviderService enqueue(ChatResponse response) {
        responses.add(response);
        return this;
    }

    public int calls() {
        return calls;
    }

    public ProviderResult call(ProviderRequest request) {
        calls++;
        ChatResponse response = responses.remove();
        return new ProviderResult(response, java.util.List.of());
    }

    public ProviderResult stream(ProviderRequest request, Consumer<String> sink) {
        calls++;
        ChatResponse response = responses.remove();
        if (response != null
                && !response.getResults().isEmpty()
                && response.getResult().getOutput().getText() != null) {
            sink.accept(response.getResult().getOutput().getText());
        }
        return new ProviderResult(response, java.util.List.of());
    }
}
