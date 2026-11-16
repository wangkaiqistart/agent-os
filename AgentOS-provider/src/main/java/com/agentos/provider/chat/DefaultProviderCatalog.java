package com.agentos.provider.chat;

import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderCatalog;

/**
 * 复用 ProviderService 外呼前的 RequestValidator，提前发现 Provider/模型参数配置问题。
 *
 * <p>resolve 只在显式连接映射中选择并校验，不扫描 ChatModel Bean，也不发送网络探测。
 * 实际远端可用性仍以 ProviderService 的请求结果为准。
 */
public final class DefaultProviderCatalog implements ProviderCatalog {
    private final RequestValidator validator;

    public DefaultProviderCatalog(RequestValidator validator) {
        this.validator = validator;
    }

    @Override
    public void validate(Profile profile) {
        // 这里只需要校验效果，无需把 ProviderConnection 暴露给依赖 core 端口的调用方。
        validator.resolve(profile);
    }
}
