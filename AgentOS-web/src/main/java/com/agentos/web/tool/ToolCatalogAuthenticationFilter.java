package com.agentos.web.tool;

import com.agentos.core.error.ErrorCode;
import com.agentos.web.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** 先认证再读目录；所有拒绝响应固定，不回显头、URL或令牌。 */
public final class ToolCatalogAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final boolean enabled;

    public ToolCatalogAuthenticationFilter(ToolQueryProperties properties) {
        enabled = properties.enabled() && properties.validToken();
        expected = digest(properties.accessToken() == null ? "" : properties.accessToken());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("摘要算法不可用");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        var headers = Collections.list(request.getHeaders("Authorization"));
        boolean urlToken =
                request.getParameterMap().keySet().stream()
                        .anyMatch(key ->
                                Set.of("token", "access_token", "api_key")
                                        .contains(key.toLowerCase(java.util.Locale.ROOT)));
        boolean accepted = false;
        if (enabled && !urlToken && headers.size() == 1) {
            String value = headers.getFirst();
            if (value.length() <= 4103 && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = value.substring(7);
                if (!token.isBlank()
                        && token.getBytes(StandardCharsets.UTF_8).length <= 4096
                        && token.codePoints()
                                .noneMatch(c ->
                                        Character.isWhitespace(c) || Character.isISOControl(c))) {
                    accepted = MessageDigest.isEqual(expected, digest(token));
                }
            }
        }
        if (!accepted) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            JsonMapper.builder()
                    .build()
                    .writeValue(
                            response.getOutputStream(),
                            ApiResponse.error(ErrorCode.UNAUTHORIZED, "未认证"));
            return;
        }
        chain.doFilter(request, response);
    }
}
