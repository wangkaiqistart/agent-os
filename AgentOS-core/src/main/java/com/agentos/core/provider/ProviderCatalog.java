package com.agentos.core.provider;

/**
 * Provider 配置的只读校验端口，供 AgentService 在申请会话租约前检查 Agent 的模型配置。
 *
 * <p>实现位于 provider 模块，core 不依赖连接实现。校验不向模型服务发请求，
 * 因而只能发现本地配置问题，不能证明远端凭证、网络或模型实际可用。
 */
public interface ProviderCatalog {
    /** 校验 Provider 选择、连接可用状态和请求参数，不消耗一次真实模型调用。 */
    void validate(Profile profile);
}
