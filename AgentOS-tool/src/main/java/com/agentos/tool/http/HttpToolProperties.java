package com.agentos.tool.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * HTTP 工具的配置和值归一化，由 ToolAutoConfiguration 装配到实际 HTTP 执行器。
 * ConfigurationProperties 注解声明前缀，不会单独把本 record 注册成 Bean。
 *
 * @param allowedDomains 允许域名列表，移除首尾空白与空项，缺省为空；实际域名校验由 WhitelistSandbox 执行
 * @param userAgent 缺省 AgentOS/0.1
 * @param maxRedirects 绑定缺省 3，显式 0 禁止重定向
 * @param maxResponseBytes 缺省且最大为 1 MiB，0 视为缺省值
 * @param timeout 正数时长，缺省 30 秒；实际连接、跳转和正文读取共用此上限
 * @param maxResultChars 普通工具结果字符上限，缺省 10000
 */
@ConfigurationProperties("agentos.tools.http")
public record HttpToolProperties(
        List<String> allowedDomains,
        String userAgent,
        @DefaultValue("3") int maxRedirects,
        int maxResponseBytes,
        Duration timeout,
        @DefaultValue("10000") int maxResultChars) {
    /** 紧凑构造器先归一化缺省值再校验；record 最终保存归一化后的不可变字段。 */
    public HttpToolProperties {
        allowedDomains =
                allowedDomains == null
                        ? List.of()
                        : allowedDomains.stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
        userAgent = userAgent == null || userAgent.isBlank() ? "AgentOS/0.1" : userAgent;
        if (maxResponseBytes == 0) {
            maxResponseBytes = 1_048_576;
        }
        if (maxRedirects < 0 || maxRedirects > 3) {
            throw new IllegalArgumentException("max-redirects 必须在 0 到 3 之间");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("max-response-bytes 超出范围");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP timeout 必须为正数");
        }
        if (maxResultChars < 1) {
            throw new IllegalArgumentException("max-result-chars 必须为正数");
        }
    }
}
