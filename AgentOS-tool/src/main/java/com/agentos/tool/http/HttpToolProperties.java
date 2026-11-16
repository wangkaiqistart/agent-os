package com.agentos.tool.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 工具的配置契约和值归一化，当前已有绑定测试，但生产装配和 HTTP 执行器尚未接入。
 * ConfigurationProperties 注解声明前缀，不会单独把本 record 注册成 Bean。
 *
 * @param allowedDomains 允许域名列表，移除首尾空白与空项，缺省为空；实际域名校验尚待执行器实现
 * @param userAgent 缺省 AgentOS/0.1
 * @param maxRedirects 缺省 3，当前传 0 也回退为 3，不能用 0 表示禁止重定向
 * @param maxResponseBytes 缺省且最大为 1 MiB，0 视为缺省值
 * @param timeout 正数时长，缺省 30 秒；声明配置不代表 I/O 层已执行该超时
 */
@ConfigurationProperties("agentos.tools.http")
public record HttpToolProperties(
        List<String> allowedDomains,
        String userAgent,
        int maxRedirects,
        int maxResponseBytes,
        Duration timeout) {
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
        if (maxRedirects == 0) {
            maxRedirects = 3;
        }
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
    }
}
