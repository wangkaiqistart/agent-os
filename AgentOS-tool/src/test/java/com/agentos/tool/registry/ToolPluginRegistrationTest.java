package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.Redactor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.ProxyFactory;

class ToolPluginRegistrationTest {
    private final JavaToolDiscovery discovery =
            new JavaToolDiscovery(new ToolPolicyResolver(), new Redactor(List::of));

    static class Plugin {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "lookup", description = "查找")
        public String lookup(String value) {
            calls.incrementAndGet();
            return value;
        }
    }

    @Test
    void discoversOfficialMethodsAndExplicitCallbackAndDeduplicatesIdentity() {
        var plugin = new Plugin();
        var explicit = ToolRegistryTest.callback("explicit");
        var beans = new LinkedHashMap<String, Object>();
        beans.put("plugin", plugin);
        beans.put("alias", plugin);
        beans.put("explicit", explicit);
        beans.put("explicitAlias", explicit);
        var definitions =
                discovery.discover(
                        beans,
                        List.of(new ToolRegistration("explicit", explicit, null)),
                        List.of());
        assertThat(definitions).extracting(d -> d.name()).containsExactly("lookup", "explicit");
        assertThat(definitions)
                .extracting(d -> d.source())
                .containsExactly("java:plugin", "java:explicit");
        assertThat(plugin.calls).hasValue(0);
        assertThat(discovery.discover(Map.of("plugin", plugin), List.of(), List.of(plugin)))
                .isEmpty();
    }

    @Test
    void proxyUsesAdviceAndRegistrationNeverInvokesBusinessMethod() {
        var target = new Plugin();
        var adviceCalls = new AtomicInteger();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(
                (MethodInterceptor)
                        invocation -> {
                            adviceCalls.incrementAndGet();
                            return invocation.proceed();
                        });
        var definitions =
                discovery.discover(Map.of("proxied", factory.getProxy()), List.of(), List.of());
        assertThat(target.calls).hasValue(0);
        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst().callback().call("{\"value\":\"one\"}")).contains("one");
        assertThat(target.calls).hasValue(1);
        assertThat(adviceCalls).hasValue(1);
    }

    @Test
    void differentObjectsCannotHideNameConflictsOrReplaceOldDirectory() {
        var registry = new ToolRegistry(List.of(ToolRegistryTest.callback("existing")));
        var before = registry.resolve(ToolRegistryTest.profile(List.of("existing")));
        assertThatThrownBy(() ->
                        registry.publish(
                                discovery.discover(
                                        Map.of("first", new Plugin(), "second", new Plugin()),
                                        List.of(),
                                        List.of()),
                                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.resolve(ToolRegistryTest.profile(List.of("existing"))).version())
                .isEqualTo(before.version());
        assertThatThrownBy(() ->
                        discovery.discover(
                                Map.of("reserved", ToolRegistryTest.callback("mcp_reserved")),
                                List.of(),
                                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidExplicitSchemaOrDescriptionDoesNotPublishPartialDirectory() {
        var registry = new ToolRegistry(List.of(ToolRegistryTest.callback("existing")));
        var version = registry.resolve(ToolRegistryTest.profile(List.of("existing"))).version();
        for (String schema :
                List.of(
                        "{",
                        "{}",
                        "{\"type\":\"object\",\"$ref\":\"https://invalid.example/schema\"}")) {
            var callback =
                    new org.springframework.ai.tool.ToolCallback() {
                        public org.springframework.ai.tool.definition.ToolDefinition
                                getToolDefinition() {
                            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                                    .name("broken")
                                    .description("无效定义")
                                    .inputSchema(schema)
                                    .build();
                        }

                        public String call(String input) {
                            throw new AssertionError("注册不得执行");
                        }
                    };
            assertThatThrownBy(() ->
                            registry.publish(
                                    discovery.discover(
                                            Map.of("broken", callback), List.of(), List.of()),
                                    List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.resolve(ToolRegistryTest.profile(List.of("existing"))).version())
                    .isEqualTo(version);
        }
    }
}
