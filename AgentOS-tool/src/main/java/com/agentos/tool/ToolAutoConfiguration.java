package com.agentos.tool;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.ToolDescriptor;
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
@EnableConfigurationProperties({
    com.agentos.tool.search.SearchProviderConfig.class,
    com.agentos.tool.interaction.InteractionToolProperties.class,
    HttpToolProperties.class,
    ToolCallingProperties.class,
    com.agentos.tool.file.FileToolProperties.class,
    com.agentos.core.config.WorkspaceProperties.class,
    com.agentos.tool.process.ShellToolProperties.class
})
public class ToolAutoConfiguration {
    @Bean
    com.agentos.tool.mcp.McpConfigurationLoader mcpConfigurationLoader() {
        return new com.agentos.tool.mcp.McpConfigurationLoader(System::getenv);
    }

    @Bean
    Sandbox sandbox(
            HttpToolProperties properties,
            com.agentos.tool.file.FileAccessPolicy files,
            com.agentos.tool.process.ShellToolProperties shell) {
        return new WhitelistSandbox(properties.allowedDomains(), files, shell);
    }

    @Bean
    com.agentos.tool.file.FileAccessPolicy fileAccessPolicy(
            com.agentos.core.config.WorkspaceProperties workspace,
            com.agentos.tool.file.FileToolProperties properties) {
        return new com.agentos.tool.file.FileAccessPolicy(workspace.root(), properties);
    }

    @Bean
    com.agentos.tool.file.FileTools fileTools(
            com.agentos.tool.file.FileAccessPolicy policy, Sandbox sandbox) {
        return new com.agentos.tool.file.FileTools(policy, sandbox);
    }

    public static java.util.List<ToolDescriptor> fileDescriptors(
            com.agentos.tool.file.FileTools files,
            com.agentos.tool.file.FileToolProperties properties,
            Redactor redactor) {
        return Arrays.stream(ToolCallbacks.from(files))
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    return new ToolDescriptor(
                            definition.name(),
                            redactor.summarize(definition.description(), 4000),
                            definition.inputSchema(),
                            "builtin",
                            null,
                            new ToolPolicy(
                                    !definition.name().equals("write_file"),
                                    properties.timeout(),
                                    properties.maxResultChars()),
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            0);
                })
                .toList();
    }

    @Bean
    com.agentos.tool.file.FileEditTools fileEditTools(
            com.agentos.tool.file.FileAccessPolicy policy, Sandbox sandbox) {
        return new com.agentos.tool.file.FileEditTools(policy, sandbox);
    }

    @Bean
    com.agentos.tool.file.FileSearchTools fileSearchTools(
            com.agentos.tool.file.FileAccessPolicy policy, Sandbox sandbox) {
        return new com.agentos.tool.file.FileSearchTools(policy, sandbox);
    }

    public static java.util.List<ToolDescriptor> extraFileDescriptors(
            Object tool, com.agentos.tool.file.FileToolProperties properties, Redactor redactor) {
        return Arrays.stream(com.agentos.tool.registry.BuiltinToolCallbacks.from(tool))
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    return new ToolDescriptor(
                            definition.name(),
                            redactor.summarize(definition.description(), 4000),
                            definition.inputSchema(),
                            "builtin",
                            null,
                            new ToolPolicy(
                                    !definition.name().equals("edit_file"),
                                    properties.timeout(),
                                    properties.maxResultChars()),
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            0);
                })
                .toList();
    }

    @Bean
    com.agentos.tool.process.ShellTools shellTools(
            com.agentos.core.config.WorkspaceProperties workspace,
            com.agentos.tool.process.ShellToolProperties properties,
            Sandbox sandbox,
            Redactor redactor) {
        return new com.agentos.tool.process.ShellTools(
                workspace.root(), properties, sandbox, redactor);
    }

    public static java.util.List<ToolDescriptor> shellDescriptors(
            com.agentos.tool.process.ShellTools shell,
            com.agentos.tool.process.ShellToolProperties properties,
            Redactor redactor) {
        return Arrays.stream(ToolCallbacks.from(shell))
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    return new ToolDescriptor(
                            definition.name(),
                            redactor.summarize(definition.description(), 4000),
                            definition.inputSchema(),
                            "builtin",
                            null,
                            new ToolPolicy(
                                    false, properties.timeout(), properties.maxResultChars()),
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            0);
                })
                .toList();
    }

    @Bean
    com.agentos.tool.search.BraveSearchProvider braveSearchProvider(
            com.agentos.tool.search.SearchProviderConfig properties,
            Sandbox sandbox,
            Redactor redactor) {
        return new com.agentos.tool.search.BraveSearchProvider(properties, sandbox, redactor);
    }

    @Bean
    com.agentos.tool.search.WebSearchTools webSearchTools(
            com.agentos.tool.search.SearchProviderConfig properties,
            com.agentos.tool.search.BraveSearchProvider provider) {
        return new com.agentos.tool.search.WebSearchTools(properties, provider);
    }

    @Bean
    com.agentos.tool.interaction.AskUserTools askUserTools(
            com.agentos.tool.interaction.InteractionToolProperties properties, Redactor redactor) {
        return new com.agentos.tool.interaction.AskUserTools(properties, redactor);
    }

    public static java.util.List<ToolDescriptor> descriptors(
            Object tool,
            ToolPolicy policy,
            Redactor redactor,
            ToolDescriptor.Availability availability) {
        return Arrays.stream(com.agentos.tool.registry.BuiltinToolCallbacks.from(tool))
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    return new ToolDescriptor(
                            definition.name(),
                            redactor.summarize(definition.description(), 4000),
                            definition.inputSchema(),
                            "builtin",
                            null,
                            policy,
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            availability,
                            0);
                })
                .toList();
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
        return ToolRegistry.fromDescriptors(
                builtinDescriptors(http, properties, redactor).stream()
                        .map(descriptor ->
                                new GovernedToolCallback(
                                                descriptor,
                                                recorder,
                                                redactor,
                                                perToolLimit(limits, descriptor.name()),
                                                requiredLimit(limits.getMaxTotalToolCalls()))
                                        .descriptor())
                        .toList());
    }

    /** 描述构造不需要审计仓库；执行装配才创建治理回调，目录模式无需伪造recorder。 */
    public static java.util.List<ToolDescriptor> builtinDescriptors(
            HttpTools http, HttpToolProperties properties, Redactor redactor) {
        return Arrays.stream(com.agentos.tool.registry.BuiltinToolCallbacks.from(http))
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    return new ToolDescriptor(
                            definition.name(),
                            redactor.summarize(definition.description(), 4000),
                            definition.inputSchema(),
                            "builtin",
                            null,
                            new ToolPolicy(
                                    definition.name().equals("http_get"),
                                    properties.timeout(),
                                    properties.maxResultChars()),
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            0);
                })
                .toList();
    }

    /** 单例完成后发现插件，避免创建目录时提前实例化依赖目录的业务Bean。 */
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton javaToolRegistration(
            @org.springframework.beans.factory.annotation.Qualifier("toolCatalog")
                    ToolCatalog catalog,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
            HttpTools http,
            com.agentos.tool.file.FileTools files,
            com.agentos.tool.process.ShellTools shell,
            com.agentos.tool.file.FileEditTools edit,
            com.agentos.tool.file.FileSearchTools search,
            com.agentos.tool.interaction.AskUserTools ask,
            com.agentos.tool.search.WebSearchTools webSearch,
            ToolInvocationRecorder recorder,
            Redactor redactor,
            ToolCallingProperties toolProperties,
            com.agentos.core.config.WorkspaceProperties workspace,
            com.agentos.tool.mcp.McpConfigurationLoader mcpLoader) {
        return () -> {
            var configured =
                    mcpLoader.load(
                            workspace.root().resolve("mcp_servers.yaml"),
                            value -> redactor.registerSecrets(java.util.List.of(value)));
            var beans = new java.util.LinkedHashMap<String, Object>();
            for (String name : beanFactory.getBeanDefinitionNames()) {
                var type = beanFactory.getType(name, false);
                Object singleton =
                        beanFactory.containsSingleton(name) ? beanFactory.getSingleton(name) : null;
                if (singleton != null) {
                    beans.put(name, singleton);
                } else if (type != null
                        && (com.agentos.tool.registry.JavaToolDiscovery.hasToolMethods(type)
                                || org.springframework.ai.tool.ToolCallback.class.isAssignableFrom(
                                        type)
                                || com.agentos.tool.registry.ToolRegistration.class
                                        .isAssignableFrom(type))) {
                    beans.put(name, beanFactory.getBean(name));
                }
            }
            var registrations =
                    beans.values().stream()
                            .filter(com.agentos.tool.registry.ToolRegistration.class::isInstance)
                            .map(com.agentos.tool.registry.ToolRegistration.class::cast)
                            .toList();
            var discovery =
                    new com.agentos.tool.registry.JavaToolDiscovery(
                            new com.agentos.tool.registry.ToolPolicyResolver(), redactor);
            var plugins =
                    discovery.discover(
                            beans,
                            registrations,
                            java.util.List.of(http, files, shell, edit, search, ask, webSearch));
            var registry = (ToolRegistry) catalog;
            var descriptors =
                    new java.util.ArrayList<ToolDescriptor>(
                            builtinDescriptors(
                                    http, beanFactory.getBean(HttpToolProperties.class), redactor));
            descriptors.addAll(
                    fileDescriptors(
                            files,
                            beanFactory.getBean(com.agentos.tool.file.FileToolProperties.class),
                            redactor));
            descriptors.addAll(
                    shellDescriptors(
                            shell,
                            beanFactory.getBean(com.agentos.tool.process.ShellToolProperties.class),
                            redactor));
            descriptors.addAll(
                    extraFileDescriptors(
                            edit,
                            beanFactory.getBean(com.agentos.tool.file.FileToolProperties.class),
                            redactor));
            descriptors.addAll(
                    extraFileDescriptors(
                            search,
                            beanFactory.getBean(com.agentos.tool.file.FileToolProperties.class),
                            redactor));
            descriptors.addAll(
                    descriptors(
                            ask,
                            new ToolPolicy(
                                    false,
                                    beanFactory
                                            .getBean(
                                                    com.agentos.tool.interaction
                                                            .InteractionToolProperties.class)
                                            .timeout(),
                                    10000),
                            redactor,
                            ToolDescriptor.Availability.AVAILABLE));
            var searchProperties =
                    beanFactory.getBean(com.agentos.tool.search.SearchProviderConfig.class);
            descriptors.addAll(
                    descriptors(
                            webSearch,
                            new ToolPolicy(true, searchProperties.timeout(), 10000),
                            redactor,
                            searchProperties.configured()
                                    ? ToolDescriptor.Availability.AVAILABLE
                                    : ToolDescriptor.Availability.UNCONFIGURED));
            descriptors.addAll(plugins);
            var limits = toolProperties.getLimits();
            registry.publish(
                    descriptors.stream()
                            .map(descriptor ->
                                    new GovernedToolCallback(
                                                    descriptor,
                                                    recorder,
                                                    redactor,
                                                    perToolLimit(limits, descriptor.name()),
                                                    requiredLimit(limits.getMaxTotalToolCalls()))
                                            .descriptor())
                            .toList(),
                    java.util.List.of());
            var mcp =
                    new com.agentos.tool.mcp.McpClientService(
                            configured.servers(),
                            workspace.root(),
                            beanFactory.getBean(com.agentos.tool.process.ShellToolProperties.class),
                            beanFactory.getBean(Sandbox.class),
                            registry,
                            descriptor ->
                                    new GovernedToolCallback(
                                                    descriptor,
                                                    recorder,
                                                    redactor,
                                                    perToolLimit(limits, descriptor.name()),
                                                    requiredLimit(limits.getMaxTotalToolCalls()))
                                            .descriptor(),
                            redactor);
            beanFactory.registerSingleton("agentosMcpClientService", mcp);
            if (beanFactory
                    instanceof
                    org.springframework.beans.factory.support.DefaultSingletonBeanRegistry
                            singletonRegistry) {
                singletonRegistry.registerDisposableBean("agentosMcpClientService", mcp::close);
            }
            mcp.configurationFailures(configured.failures());
            mcp.start();
        };
    }

    @Bean
    com.agentos.core.tool.ToolCatalogQuery toolCatalogQuery(
            @org.springframework.beans.factory.annotation.Qualifier("toolCatalog")
                    ToolCatalog catalog,
            com.agentos.core.config.WorkspaceProperties workspace,
            Redactor redactor) {
        return new com.agentos.tool.registry.DefaultToolCatalogQuery(
                (ToolRegistry) catalog,
                new com.agentos.core.agent.AgentLoader(workspace.root().resolve("agents")),
                redactor);
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
