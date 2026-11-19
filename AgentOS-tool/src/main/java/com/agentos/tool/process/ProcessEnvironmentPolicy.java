package com.agentos.tool.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 仅保留部署者明确指定的基础变量和本次程序/服务自己的显式变量。 */
public final class ProcessEnvironmentPolicy {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final ShellToolProperties properties;
    private final Function<String, String> environment;

    public ProcessEnvironmentPolicy(ShellToolProperties properties) {
        this(properties, System::getenv);
    }

    public ProcessEnvironmentPolicy(
            ShellToolProperties properties, Function<String, String> environment) {
        this.properties = Objects.requireNonNull(properties);
        this.environment = Objects.requireNonNull(environment);
    }

    public Map<String, String> build(Map<String, String> explicit) {
        var result = new LinkedHashMap<String, String>();
        for (String name : properties.inheritEnvironment()) {
            String value = environment.apply(name);
            if (value != null) {
                ShellToolProperties.validateEnvironmentEntry(name, value);
                result.put(name, value);
            }
        }
        explicit.forEach((name, configured) -> {
            ShellToolProperties.validateEnvironmentEntry(name, configured);
            String value = resolve(configured, ignored -> {});
            if (value == null) {
                throw new ShellToolException("UNCONFIGURED：环境引用缺失或格式无效");
            }
            result.put(name, value);
        });
        return Map.copyOf(result);
    }

    /** 装配时只解析命令显式指定的值；缺失项不妨碍其他工具，调用时仍严格拒绝。 */
    public List<String> configuredSecrets() {
        var secrets = new ArrayList<String>();
        for (var command : properties.commands()) {
            for (String configured : command.env().values()) {
                String value = resolve(configured, secrets::add);
                if (value != null) {
                    secrets.add(value);
                }
            }
        }
        return List.copyOf(secrets);
    }

    private String resolve(String configured, Consumer<String> secret) {
        var matcher = REFERENCE.matcher(configured);
        var value = new StringBuilder();
        boolean missing = false;
        while (matcher.find()) {
            String resolved = environment.apply(matcher.group(1));
            if (resolved == null || resolved.indexOf(0) >= 0) {
                missing = true;
                continue;
            }
            // 同时保护组合配置中的原始引用值，避免仅登记前后缀拼接后的字符串。
            secret.accept(resolved);
            matcher.appendReplacement(value, java.util.regex.Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(value);
        return missing || value.toString().contains("${") ? null : value.toString();
    }
}
