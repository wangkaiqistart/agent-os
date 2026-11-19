package com.agentos.boot;

import com.agentos.cli.ContextLauncher;
import com.agentos.core.tool.ToolCatalogQuery;
import com.agentos.web.tool.ToolQueryProperties;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** 目录配置按显式YAML读取，不处理其spring.config.import，不读取.env或创建执行链。 */
public final class ToolCatalogContextLauncher {
    public ContextLauncher.CatalogContext launch(Path root, Path config, boolean serve) {
        var context = open(root, config, serve);
        var closed = new CountDownLatch(1);
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.event.ContextClosedEvent) {
                closed.countDown();
            }
        });
        return new ContextLauncher.CatalogContext() {
            public ToolCatalogQuery query() {
                return context.getBean(ToolCatalogQuery.class);
            }

            public void await() throws InterruptedException {
                closed.await();
            }

            public void close() {
                context.close();
                closed.countDown();
            }
        };
    }

    org.springframework.context.ConfigurableApplicationContext open(
            Path root, Path config, boolean serve) {
        return open(root, config, serve, new Class<?>[0]);
    }

    org.springframework.context.ConfigurableApplicationContext open(
            Path root, Path config, boolean serve, Class<?>... additional) {
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("工作区路径不合法");
        }
        var environment = new StandardEnvironment();
        if (config != null) {
            if (!config.isAbsolute() || !java.nio.file.Files.isRegularFile(config)) {
                throw new IllegalArgumentException("目录配置路径不可用");
            }
            try {
                for (var source :
                        new org.springframework.boot.env.YamlPropertySourceLoader()
                                .load(
                                        "catalog-config",
                                        new org.springframework.core.io.FileSystemResource(
                                                config))) {
                    environment.getPropertySources().addLast(source);
                }
            } catch (java.io.IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("目录配置不可用");
            }
        }
        ToolQueryProperties properties;
        try {
            properties =
                    Binder.get(environment)
                            .bind("agentos.tools.query", ToolQueryProperties.class)
                            .orElse(new ToolQueryProperties(false, "127.0.0.1", 8080, ""));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("目录监听配置不合法");
        }
        if (properties == null) {
            throw new IllegalArgumentException("目录监听配置不合法");
        }
        if (serve && (!properties.enabled() || !properties.validToken())) {
            throw new IllegalArgumentException("目录服务需要启用并配置有效令牌");
        }
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "catalog-command",
                                Map.of(
                                        "agentos.workspace.root",
                                        root.toString(),
                                        "agentos.runtime.catalog",
                                        "true",
                                        "spring.config.name",
                                        "agentos-tool-catalog",
                                        "spring.config.import",
                                        "",
                                        "spring.main.banner-mode",
                                        "off",
                                        "spring.main.log-startup-info",
                                        "false",
                                        "logging.level.root",
                                        "OFF",
                                        "server.address",
                                        properties.bindAddress(),
                                        "server.port",
                                        properties.port())));
        return new SpringApplicationBuilder(ToolCatalogApplication.class)
                .sources(additional)
                .environment(environment)
                .web(serve ? WebApplicationType.SERVLET : WebApplicationType.NONE)
                .logStartupInfo(false)
                .run();
    }
}
