package com.agentos.provider.chat;

import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderCatalog;

/** 复用 ProviderService 外呼前校验，不扫描 ChatModel Bean。 */
public final class DefaultProviderCatalog implements ProviderCatalog {
    private final RequestValidator validator;

    public DefaultProviderCatalog(RequestValidator validator) {
        this.validator = validator;
    }

    @Override
    public void validate(Profile profile) {
        validator.resolve(profile);
    }
}
