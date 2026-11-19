package com.agentos.tool.search;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.tool.http.BoundedHttpIo;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Brave GET q/count协议；凭证只在服务头，结果页面不被抓取。 */
public final class BraveSearchProvider implements SearchProvider {
    private final SearchProviderConfig config;
    private final Sandbox sandbox;
    private final Redactor redactor;
    private final HttpClient client;

    public BraveSearchProvider(SearchProviderConfig config, Sandbox sandbox, Redactor redactor) {
        this.config = config;
        this.sandbox = sandbox;
        this.redactor = redactor;
        if (config.apiKey() != null) {
            redactor.registerSecrets(List.of(config.apiKey()));
        }
        client =
                HttpClient.newBuilder()
                        .connectTimeout(config.timeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public SearchResult search(String query, int limit, ToolExecutionScope scope) {
        scope.checkpoint();
        if (query == null
                || query.isBlank()
                || query.length() > config.maxQueryChars()
                || limit < 1
                || limit > config.maxResults()) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
        if (!config.configured()) {
            throw new IllegalStateException("UNCONFIGURED");
        }
        try {
            sandbox.enforce(new SandboxAction(ActionType.HTTP, config.endpoint()));
        } catch (com.agentos.core.tool.ApprovalRequiredException configured) {
            // 搜索端点由部署者配置，本身就是授权；只有非法地址等硬拒绝才拦截。
        }
        URI endpoint;
        try {
            endpoint = URI.create(config.endpoint());
            if (endpoint.getRawQuery() != null
                    || endpoint.getFragment() != null
                    || endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("FAILED：搜索地址配置非法");
        }
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint
                                                + "?q="
                                                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                                                + "&count="
                                                + limit))
                        .header("X-Subscription-Token", config.apiKey())
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        var response = BoundedHttpIo.exchange(client, request, scope, config.maxResponseBytes());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("FAILED：搜索服务响应失败");
        }
        var items = new ArrayList<SearchResult.Item>();
        boolean truncated = false;
        try {
            var json = JsonMapper.builder().build().readTree(response.body());
            var results = json.path("web").path("results");
            if (!results.isArray()) {
                throw new IllegalArgumentException();
            }
            for (var item : results) {
                scope.checkpoint();
                if (items.size() >= limit) {
                    truncated = true;
                    break;
                }
                if (!item.path("title").isString()
                        || !item.path("url").isString()
                        || !item.path("description").isString()) {
                    throw new IllegalArgumentException();
                }
                String url = redactor.redact(item.path("url").asText());
                if (!publicReference(url)) {
                    truncated = true;
                    continue;
                }
                String title = redactor.redact(item.path("title").asText());
                String snippet = redactor.redact(item.path("description").asText());
                if (title.length() > 2000 || snippet.length() > 4000) {
                    truncated = true;
                }
                items.add(
                        new SearchResult.Item(
                                title.substring(0, Math.min(title.length(), 2000)),
                                url,
                                snippet.substring(0, Math.min(snippet.length(), 4000))));
            }
        } catch (com.agentos.core.turn.TurnDeadlineExceededException
                | com.agentos.core.turn.LeaseLostException
                | ToolExecutionScope.ToolTimeoutException fatal) {
            throw fatal;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("FAILED：搜索响应格式非法");
        }
        return new SearchResult(items, truncated, true);
    }

    private boolean publicReference(String value) {
        if (value.length() > 2000) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
