package com.agentos.tool;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.tool.govern.AgentOsToolExecutionExceptionProcessor;
import com.agentos.tool.govern.GovernedToolCallback;
import com.agentos.tool.govern.UnknownToolResolver;
import com.agentos.tool.http.HttpToolProperties;
import com.agentos.tool.http.HttpTools;
import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.sandbox.WhitelistSandbox;
import java.util.Arrays;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 先于官方工具配置注册安全解析器/异常处理器；不自行创建或替换官方 Manager。 */
@AutoConfiguration(
        beforeName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration",
        afterName = "com.agentos.storage.StorageAutoConfiguration")
@EnableConfigurationProperties({HttpToolProperties.class, ToolCallingProperties.class})
public class ToolAutoConfiguration {
    @Bean
    Sandbox sandbox(HttpToolProperties properties) {
        return new WhitelistSandbox(properties.allowedDomains());
    }

    @Bean
    HttpTools httpTools(HttpToolProperties properties, Sandbox sandbox) {
        return new HttpTools(properties, sandbox);
    }

    @Bean
    ToolCatalog toolCatalog(
            HttpTools http,
            HttpToolProperties properties,
            ToolInvocationRecorder recorder,
            Redactor redactor,
            ToolCallingProperties toolProperties) {
        var limits = toolProperties.getLimits();
        return new ToolRegistry(
                Arrays.stream(ToolCallbacks.from(http))
                        .map(cb ->
                                (org.springframework.ai.tool.ToolCallback)
                                        new GovernedToolCallback(
                                                cb,
                                                new ToolPolicy(
                                                        true,
                                                        properties.timeout(),
                                                        properties.maxResultChars()),
                                                recorder,
                                                redactor,
                                                perToolLimit(limits, cb.getToolDefinition().name()),
                                                requiredLimit(limits.getMaxTotalToolCalls())))
                        .toList());
    }

    @Bean
    ToolCallbackResolver toolCallbackResolver(
            ToolInvocationRecorder recorder,
            Redactor redactor,
            ToolCallingProperties toolProperties) {
        var limits = toolProperties.getLimits();
        return new UnknownToolResolver(
                recorder,
                redactor,
                name -> perToolLimit(limits, name),
                requiredLimit(limits.getMaxTotalToolCalls()));
    }

    private static int perToolLimit(ToolCallingProperties.Limits limits, String name) {
        return limits.getExcludedTools().contains(name)
                ? -1
                : requiredLimit(
                        limits.getMaxCallsPerTool()
                                .getOrDefault(name, limits.getMaxCallsPerToolDefault()));
    }

    private static int requiredLimit(Integer value) {
        if (value == null || value < -1) {
            throw new IllegalArgumentException("工具限次必须非空，且非负或为 -1");
        }
        return value;
    }

    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor(Redactor redactor) {
        return new AgentOsToolExecutionExceptionProcessor(redactor);
    }
}
