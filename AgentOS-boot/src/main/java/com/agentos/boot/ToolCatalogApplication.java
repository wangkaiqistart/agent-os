package com.agentos.boot;

import com.agentos.core.config.WorkspaceProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.tool.ToolAutoConfiguration;
import com.agentos.tool.file.FileAccessPolicy;
import com.agentos.tool.file.FileEditTools;
import com.agentos.tool.file.FileSearchTools;
import com.agentos.tool.file.FileToolProperties;
import com.agentos.tool.file.FileTools;
import com.agentos.tool.http.HttpToolProperties;
import com.agentos.tool.http.HttpTools;
import com.agentos.tool.interaction.AskUserTools;
import com.agentos.tool.interaction.InteractionToolProperties;
import com.agentos.tool.process.ShellToolProperties;
import com.agentos.tool.process.ShellTools;
import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.search.BraveSearchProvider;
import com.agentos.tool.search.SearchProviderConfig;
import com.agentos.tool.search.WebSearchTools;
import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 显式最小装配：没有组件扫描、数据库、Provider、Manager或伪审计器。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.runtime.catalog", havingValue = "true")
@EnableConfigurationProperties({
    WorkspaceProperties.class,
    FileToolProperties.class,
    HttpToolProperties.class,
    ShellToolProperties.class,
    InteractionToolProperties.class,
    SearchProviderConfig.class,
    com.agentos.web.tool.ToolQueryProperties.class
})
@ImportAutoConfiguration({
    org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
            .class,
    org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
    org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
            .class
})
@Import({
    com.agentos.web.tool.ToolCatalogController.class,
    com.agentos.web.tool.ToolCatalogWebConfiguration.class
})
public class ToolCatalogApplication {
    @Bean
    com.agentos.tool.mcp.McpConfigurationLoader mcpConfigurationLoader() {
        return new com.agentos.tool.mcp.McpConfigurationLoader(System::getenv);
    }

    @Bean
    Redactor catalogRedactor() {
        return new Redactor(List::of);
    }

    @Bean
    FileAccessPolicy catalogFiles(WorkspaceProperties workspace, FileToolProperties properties) {
        return new FileAccessPolicy(workspace.root(), properties);
    }

    @Bean
    com.agentos.core.tool.Sandbox catalogSandbox(
            HttpToolProperties http, FileAccessPolicy file, ShellToolProperties shell) {
        return new com.agentos.tool.sandbox.WhitelistSandbox(http.allowedDomains(), file, shell);
    }

    @Bean
    HttpTools catalogHttp(HttpToolProperties properties, com.agentos.core.tool.Sandbox sandbox) {
        return new HttpTools(properties, sandbox);
    }

    @Bean
    FileTools catalogFileTools(FileAccessPolicy policy, com.agentos.core.tool.Sandbox sandbox) {
        return new FileTools(policy, sandbox);
    }

    @Bean
    FileEditTools catalogEdit(FileAccessPolicy policy, com.agentos.core.tool.Sandbox sandbox) {
        return new FileEditTools(policy, sandbox);
    }

    @Bean
    FileSearchTools catalogSearch(FileAccessPolicy policy, com.agentos.core.tool.Sandbox sandbox) {
        return new FileSearchTools(policy, sandbox);
    }

    @Bean
    ShellTools catalogShell(
            WorkspaceProperties workspace,
            ShellToolProperties properties,
            com.agentos.core.tool.Sandbox sandbox,
            Redactor redactor) {
        return new ShellTools(workspace.root(), properties, sandbox, redactor);
    }

    @Bean
    AskUserTools catalogAsk(InteractionToolProperties properties, Redactor redactor) {
        return new AskUserTools(properties, redactor);
    }

    @Bean
    WebSearchTools catalogWebSearch(
            SearchProviderConfig properties,
            com.agentos.core.tool.Sandbox sandbox,
            Redactor redactor) {
        return new WebSearchTools(
                properties, new BraveSearchProvider(properties, sandbox, redactor));
    }

    @Bean
    com.agentos.core.agent.AgentLoader catalogAgentLoader(WorkspaceProperties workspace) {
        return new com.agentos.core.agent.AgentLoader(workspace.root().resolve("agents"));
    }

    @Bean
    com.agentos.core.tool.ToolCatalogQuery catalogQuery(
            ToolRegistry registry, com.agentos.core.agent.AgentLoader loader, Redactor redactor) {
        return new com.agentos.tool.registry.DefaultToolCatalogQuery(registry, loader, redactor);
    }

    @Bean
    ToolRegistry catalogRegistry(
            HttpTools http,
            HttpToolProperties httpProperties,
            FileTools files,
            FileEditTools edit,
            FileSearchTools search,
            FileToolProperties fileProperties,
            ShellTools shell,
            ShellToolProperties shellProperties,
            AskUserTools ask,
            InteractionToolProperties interaction,
            WebSearchTools web,
            SearchProviderConfig searchProperties,
            Redactor redactor) {
        var descriptors =
                new java.util.ArrayList<ToolDescriptor>(
                        ToolAutoConfiguration.builtinDescriptors(http, httpProperties, redactor));
        descriptors.addAll(ToolAutoConfiguration.fileDescriptors(files, fileProperties, redactor));
        descriptors.addAll(
                ToolAutoConfiguration.extraFileDescriptors(edit, fileProperties, redactor));
        descriptors.addAll(
                ToolAutoConfiguration.extraFileDescriptors(search, fileProperties, redactor));
        descriptors.addAll(
                ToolAutoConfiguration.shellDescriptors(shell, shellProperties, redactor));
        descriptors.addAll(
                ToolAutoConfiguration.descriptors(
                        ask,
                        new ToolPolicy(false, interaction.timeout(), 10000),
                        redactor,
                        ToolDescriptor.Availability.AVAILABLE));
        descriptors.addAll(
                ToolAutoConfiguration.descriptors(
                        web,
                        new ToolPolicy(true, searchProperties.timeout(), 10000),
                        redactor,
                        searchProperties.configured()
                                ? ToolDescriptor.Availability.AVAILABLE
                                : ToolDescriptor.Availability.UNCONFIGURED));
        return ToolRegistry.fromDescriptors(descriptors);
    }

    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton catalogMaintenance(
            ToolRegistry registry,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory,
            WorkspaceProperties workspace,
            ShellToolProperties shell,
            com.agentos.core.tool.Sandbox sandbox,
            Redactor redactor,
            HttpTools http,
            FileTools files,
            FileEditTools edit,
            FileSearchTools search,
            ShellTools process,
            AskUserTools ask,
            WebSearchTools web,
            com.agentos.tool.mcp.McpConfigurationLoader mcpLoader) {
        return () -> {
            var configured =
                    mcpLoader.load(
                            workspace.root().resolve("mcp_servers.yaml"),
                            value -> redactor.registerSecrets(java.util.List.of(value)));
            var beans = new java.util.LinkedHashMap<String, Object>();
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                Object bean = factory.containsSingleton(name) ? factory.getSingleton(name) : null;
                if (bean != null) {
                    beans.put(name, bean);
                } else if (type != null
                        && (com.agentos.tool.registry.JavaToolDiscovery.hasToolMethods(type)
                                || org.springframework.ai.tool.ToolCallback.class.isAssignableFrom(
                                        type)
                                || com.agentos.tool.registry.ToolRegistration.class
                                        .isAssignableFrom(type))) {
                    beans.put(name, factory.getBean(name));
                }
            }
            var registrations =
                    beans.values().stream()
                            .filter(com.agentos.tool.registry.ToolRegistration.class::isInstance)
                            .map(com.agentos.tool.registry.ToolRegistration.class::cast)
                            .toList();
            var plugins =
                    new com.agentos.tool.registry.JavaToolDiscovery(
                                    new com.agentos.tool.registry.ToolPolicyResolver(), redactor)
                            .discover(
                                    beans,
                                    registrations,
                                    List.of(http, files, edit, search, process, ask, web));
            var descriptors = new java.util.ArrayList<>(registry.descriptors());
            descriptors.addAll(plugins);
            registry.publish(descriptors, List.of());
            var mcp =
                    new com.agentos.tool.mcp.McpClientService(
                            configured.servers(),
                            workspace.root(),
                            shell,
                            sandbox,
                            registry,
                            java.util.function.Function.identity(),
                            redactor);
            factory.registerSingleton("catalogMcp", mcp);
            if (factory
                    instanceof
                    org.springframework.beans.factory.support.DefaultSingletonBeanRegistry
                            singletons) {
                singletons.registerDisposableBean("catalogMcp", mcp::close);
            }
            mcp.configurationFailures(configured.failures());
            mcp.start();
        };
    }
}
