package com.agentos.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具审计和对外错误共用的脱敏器，只通过 Supplier 获取已知密钥，不依赖连接配置类型。
 * JSON 先解析再处理，避免切断转义字符串或破坏审计结构；非 JSON 文本使用有限的键值规则。
 * 本类不记录输入，也不把解析器的原始异常向外传播。
 */
public final class Redactor {
    private static final String MASK = "***";
    private static final Set<String> KEYS =
            Set.of(
                    "authorization",
                    "api_key",
                    "apikey",
                    "token",
                    "access_token",
                    "secret",
                    "password",
                    "key",
                    "sign");
    private static final Pattern QUERY =
            Pattern.compile("(?i)([?&](?:key|token|access_token|sign)=)[^&#\\s\"']*");
    private static final Pattern FIELD =
            Pattern.compile(
                    "(?i)(\\b(?:authorization|api_key|apikey|token|access_token|secret|password)[\"']?\\s*[=:]\\s*)(?:(?:Bearer|Basic)\\s+)?(?:\"(?:\\\\.|[^\"\\\\])*(?:\"|$)|'[^']*(?:'|$)|[^\\s,;&}\\]]+)");
    private final Supplier<? extends Collection<String>> secrets;
    private final JsonMapper json = JsonMapper.builder().build();

    public Redactor(Supplier<? extends Collection<String>> secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    private final java.util.Set<String> registeredSecrets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void registerSecrets(Collection<String> values) {
        values.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .forEach(registeredSecrets::add);
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            Object value = json.readValue(text, Object.class);
            return json.writeValueAsString(redactValue(value));
        } catch (RuntimeException ignored) {
            // 非 JSON 或损坏参数也必须保留一份安全文本，由存储层作为 JSON 字符串值保存。
            return redactText(text);
        }
    }

    /** 审计和过程事件共用的安全摘要；先脱敏，截断标记也计入字符上限。 */
    public String summarize(String text, int maxChars) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("摘要长度不能为负数");
        }
        String safe = redact(text);
        if (safe == null || safe.length() <= maxChars) {
            return safe;
        }
        String marker = "[摘要截断]";
        return maxChars <= marker.length()
                ? marker.substring(0, maxChars)
                : safe.substring(0, maxChars - marker.length()) + marker;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                safe.put(
                        redactText(name),
                        KEYS.contains(name.toLowerCase(Locale.ROOT)) ? MASK : redactValue(item));
            });
            return safe;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactValue).toList();
        }
        return value instanceof String string ? redactText(string) : value;
    }

    private String redactText(String text) {
        String safe = QUERY.matcher(text).replaceAll("$1" + MASK);
        safe = FIELD.matcher(safe).replaceAll("$1" + MASK);
        Collection<String> provided = secrets.get();
        List<String> values = new ArrayList<>();
        if (provided != null) {
            provided.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .forEach(values::add);
        }
        values.addAll(registeredSecrets);
        // 长密钥先替换，避免短密钥先替换后留下长密钥的尾部。
        values.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : values) {
            safe = safe.replace(secret, MASK);
            String escaped = json.writeValueAsString(secret);
            safe = safe.replace(escaped.substring(1, escaped.length() - 1), MASK);
        }
        return safe;
    }
}
