package com.agentos.provider.config;

import com.agentos.core.error.AgentOSException;
import com.agentos.provider.chat.ChatModelFactory;
import com.agentos.provider.chat.ConnectionType;
import com.agentos.provider.chat.ProviderConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验 {@link ProviderProperties} 并建好不可变的连接映射。
 *
 * <p>校验规则见 contracts/configuration.md「启动校验」。所有问题收集后一次性报出，
 * 这样运维补完一个环境变量不会又撞上下一个错误。
 *
 * <p>TODO(US-5)：迁入 ConfigLoader。US-1 暂由 Spring 解析 {@code ${ENV_VAR}} 占位符，本类负责结构和必填校验。
 */
final class ProviderConnectionAssembler {

    private static final Logger log = LoggerFactory.getLogger(ProviderConnectionAssembler.class);

    private final ProviderProperties properties;

    /** 按属性键取配置原文（占位符尚未解析）；取不到返回 null。 */
    private final Function<String, String> rawValue;

    private final ChatModelFactory factory;

    ProviderConnectionAssembler(
            ProviderProperties properties,
            Function<String, String> rawValue,
            ChatModelFactory factory) {
        this.properties = properties;
        this.rawValue = rawValue;
        this.factory = factory;
    }

    /**
     * 校验配置并建立连接。
     *
     * @return 连接名（保持配置里的写法）到连接的映射，不可变
     * @throws IllegalStateException 配置有误；消息列出全部问题，不含密钥
     */
    Map<String, ProviderConnection> assemble() {
        List<String> problems = new ArrayList<>();
        Map<String, ProviderProperties.Connection> providers = properties.getProviders();
        checkNameConflicts(providers, problems);
        String defaultName = properties.getDefaultProvider();
        if (find(providers, defaultName) == null) {
            problems.add("默认连接 " + defaultName + " 不存在，请在 agentos.providers 下配置它");
        }

        Map<String, ProviderConnection> result = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderProperties.Connection> entry : providers.entrySet()) {
            boolean isDefault = entry.getKey().equalsIgnoreCase(defaultName);
            ProviderConnection connection =
                    build(entry.getKey(), entry.getValue(), isDefault, problems);
            if (connection != null) {
                result.put(entry.getKey(), connection);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Provider 配置有误：" + String.join("；", problems));
        }
        return Map.copyOf(result);
    }

    private ProviderConnection build(
            String name,
            ProviderProperties.Connection config,
            boolean isDefault,
            List<String> problems) {
        int before = problems.size();
        ConnectionType type = parseType(name, config.getType(), problems);
        if (!hasText(config.getBaseUrl())) {
            problems.add("连接 " + name + " 的 base-url 为空");
        }
        Duration timeout = config.getTimeout();
        checkDurations(name, config, timeout, problems);

        boolean available = hasText(config.getApiKey());
        String envVar = envVarName(name);
        if (!available && isDefault) {
            problems.add("默认连接 " + name + " 的 api-key 为空，" + keyHint(envVar));
        }
        if (problems.size() > before) {
            return null;
        }
        if (!available) {
            log.info("provider 不可用：{}，{}", name, keyHint(envVar));
            return new ProviderConnection(
                    name,
                    type,
                    timeout,
                    config.getRetryMinRemaining(),
                    config.getRetryMaxWait(),
                    false,
                    envVar,
                    null);
        }
        return new ProviderConnection(
                name,
                type,
                timeout,
                config.getRetryMinRemaining(),
                config.getRetryMaxWait(),
                true,
                envVar,
                factory.create(config));
    }

    private static ConnectionType parseType(String name, String raw, List<String> problems) {
        if (!hasText(raw)) {
            problems.add("连接 " + name + " 没有配置 type");
            return null;
        }
        try {
            return ConnectionType.parse(raw.trim());
        } catch (AgentOSException unsupported) {
            problems.add("连接 " + name + " 的类型 " + raw + " 暂不支持，目前只支持 openai、deepseek");
            return null;
        }
    }

    private static void checkDurations(
            String name,
            ProviderProperties.Connection config,
            Duration timeout,
            List<String> problems) {
        if (!isPositive(timeout)) {
            problems.add("连接 " + name + " 的 timeout 必须是正数，当前是 " + timeout);
            return;
        }
        Duration minRemaining = config.getRetryMinRemaining();
        Duration maxWait = config.getRetryMaxWait();
        boolean minValid = isPositive(minRemaining) && minRemaining.compareTo(timeout) <= 0;
        if (!minValid) {
            problems.add(
                    "连接 "
                            + name
                            + " 的 retry-min-remaining 必须是正数且不大于 timeout，当前 retry-min-remaining="
                            + minRemaining
                            + "，timeout="
                            + timeout);
        }
        // retry-max-wait 要和 retry-min-remaining 比较，后者本身不合法时比较没有意义。
        boolean maxValid =
                isPositive(maxWait) && (!minValid || maxWait.compareTo(minRemaining) >= 0);
        if (!maxValid) {
            problems.add(
                    "连接 "
                            + name
                            + " 的 retry-max-wait 必须是正数且不小于 retry-min-remaining，当前 retry-max-wait="
                            + maxWait
                            + "，retry-min-remaining="
                            + minRemaining);
        }
    }

    /** YAML 的 map 键本身唯一，这里防止 Primary 和 primary 并存。 */
    private static void checkNameConflicts(
            Map<String, ProviderProperties.Connection> providers, List<String> problems) {
        Map<String, String> seen = new HashMap<>();
        for (String name : providers.keySet()) {
            String previous = seen.put(name.toLowerCase(Locale.ROOT), name);
            if (previous != null) {
                problems.add("连接名重复（不区分大小写）：" + previous + " 与 " + name);
            }
        }
    }

    private static ProviderProperties.Connection find(
            Map<String, ProviderProperties.Connection> providers, String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, ProviderProperties.Connection> entry : providers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 从配置原文的 ${...} 取环境变量名；密钥不是占位符写法时返回 null。 */
    private String envVarName(String connectionName) {
        String raw = rawValue.apply("agentos.providers." + connectionName + ".api-key");
        if (raw == null) {
            return null;
        }
        return placeholderName(raw);
    }

    /**
     * 取 {@code ${VAR}} 或 {@code ${VAR:默认值}} 里的变量名；不是整段占位符时返回 null。
     *
     * <p>用字符串截取而不是正则，避免正则回溯带来的性能风险。
     */
    private static String placeholderName(String raw) {
        String text = raw.trim();
        if (!text.startsWith("${") || !text.endsWith("}")) {
            return null;
        }
        String inner = text.substring(2, text.length() - 1);
        int colon = inner.indexOf(':');
        String name = (colon < 0 ? inner : inner.substring(0, colon)).trim();
        return name.isEmpty() ? null : name;
    }

    private static String keyHint(String envVar) {
        return envVar == null ? "缺少 API key" : "缺少环境变量 " + envVar;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
