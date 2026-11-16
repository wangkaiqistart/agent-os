package com.agentos.cli;

/** 只携带生成 main 所需的非敏感值，不保存环境 Map、连接串或凭据。 */
public record DefaultAgentOptions(String model, String providerName) {
    public DefaultAgentOptions {
        if (model == null
                || model.isBlank()
                || model.contains("${")
                || model.contains("{{")
                || (model.startsWith("<") && model.endsWith(">"))) {
            throw new IllegalArgumentException("model 必须是具体非空白值，请提供 --model 或 AGENTOS_LLM_MODEL");
        }
        if (providerName != null && providerName.isBlank()) {
            throw new IllegalArgumentException("provider 必须是非空白连接名");
        }
    }
}
