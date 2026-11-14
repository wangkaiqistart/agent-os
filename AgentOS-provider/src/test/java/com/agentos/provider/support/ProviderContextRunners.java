package com.agentos.provider.support;

import com.agentos.provider.config.ProviderAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 构造装配了 Provider 自动配置的上下文运行器，让测试走真实的 Spring 配置路径。
 *
 * <p>ApplicationContextRunner 只刷新上下文，不启动 Web 服务器，每个测试方法创建一次也很便宜。
 */
public final class ProviderContextRunners {

    private ProviderContextRunners() {}

    /** 只装配 Provider 自动配置和官方 ToolCallingManager，不带任何连接配置。 */
    public static ApplicationContextRunner base() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
                .withUserConfiguration(TestSpringAiConfiguration.class);
    }

    /**
     * 生成一个连接的全部配置项，超时固定 5 秒。
     *
     * @param name 连接名，即 agentos.providers 下的键
     * @param type 连接类型字符串，原样写入，便于测试非法类型
     * @param baseUrl 服务地址；传 null 时不写该项
     * @param apiKey 密钥或占位符；传 null 时不写该项
     */
    public static String[] connection(String name, String type, String baseUrl, String apiKey) {
        String prefix = "agentos.providers." + name + ".";
        java.util.List<String> values = new java.util.ArrayList<>();
        values.add(prefix + "type=" + type);
        values.add(prefix + "timeout=5s");
        // 缺省的 retry-min-remaining 是 15s，大于这里的 5s 超时，按规则非法，所以显式给一个合法值。
        values.add(prefix + "retry-min-remaining=1s");
        if (baseUrl != null) {
            values.add(prefix + "base-url=" + baseUrl);
        }
        if (apiKey != null) {
            values.add(prefix + "api-key=" + apiKey);
        }
        return values.toArray(String[]::new);
    }

    /** 把多组配置项拼成一个数组，方便传给 withPropertyValues。 */
    public static String[] concat(String[]... groups) {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String[] group : groups) {
            all.addAll(java.util.List.of(group));
        }
        return all.toArray(String[]::new);
    }
}
