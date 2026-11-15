package com.agentos.core.provider;

/** Provider 配置的只读校验端口。 */
public interface ProviderCatalog {
    void validate(Profile profile);
}
