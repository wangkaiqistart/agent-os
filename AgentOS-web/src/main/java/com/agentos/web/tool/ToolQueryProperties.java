package com.agentos.web.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 仅目录入口的部署权限；不代表用户身份体系。 */
@ConfigurationProperties("agentos.tools.query")
public record ToolQueryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("127.0.0.1") String bindAddress,
        @DefaultValue("8080") int port,
        @DefaultValue("") String accessToken) {
    public ToolQueryProperties {
        if (bindAddress == null || bindAddress.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("目录监听配置不合法");
        }
    }

    public boolean validToken() {
        return accessToken != null
                && accessToken.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 16
                && accessToken.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096
                && !accessToken.isBlank()
                && accessToken
                        .codePoints()
                        .noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    @Override
    public String toString() {
        return "ToolQueryProperties[redacted]";
    }
}
