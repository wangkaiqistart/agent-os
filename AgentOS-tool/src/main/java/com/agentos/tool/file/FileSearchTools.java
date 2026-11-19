package com.agentos.tool.file;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/** 字面内容与便携glob搜索；有界扫描、保留和结构化结果，不跟随目录链接。 */
public final class FileSearchTools {
    private record Match(String path, int line, String content) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FileAccessPolicy policy;
    private final Sandbox sandbox;
    private final BoundedFileScanner scanner;
    private final FileTools reader;

    public FileSearchTools(FileAccessPolicy policy, Sandbox sandbox) {
        this.policy = policy;
        this.sandbox = sandbox;
        scanner = new BoundedFileScanner(policy, sandbox);
        reader = new FileTools(policy, sandbox);
    }

    private ToolExecutionScope scope(ToolContext context) {
        var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        scope.checkpoint();
        return scope;
    }

    private int limit(Integer requested) {
        int cap = requested == null ? policy.properties().maxResults() : requested;
        if (cap < 1 || cap > policy.properties().maxResults()) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        return cap;
    }

    @Tool(name = "grep", description = "在已授权UTF-8文件中按字面区分大小写查找，返回相对路径、1起行号和有界内容，标明截断与不完整")
    public String grep(
            @ToolParam(description = "目录或文件路径") String path,
            @ToolParam(description = "非空白字面文本，不是正则") String text,
            @ToolParam(description = "最大结果数，默认200", required = false) Integer limit,
            ToolContext context) {
        var scope = scope(context);
        int cap = limit(limit);
        if (text == null || text.isBlank()) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
        var matches =
                new TreeSet<Match>(Comparator.comparing(Match::path).thenComparingInt(Match::line));
        boolean[] truncated = {false};
        var scanned =
                scanner.scan(
                        path,
                        scope,
                        (relative, version) -> {
                            scope.checkpoint();
                            String content = reader.readText(version, scope);
                            var lines = content.lines().iterator();
                            int line = 0;
                            while (lines.hasNext()) {
                                scope.checkpoint();
                                String value = lines.next();
                                line++;
                                if (value.contains(text)) {
                                    int length = Math.min(value.length(), 1500);
                                    if (length < value.length()) {
                                        truncated[0] = true;
                                    }
                                    matches.add(
                                            new Match(relative, line, value.substring(0, length)));
                                    if (matches.size() > cap) {
                                        matches.pollLast();
                                        truncated[0] = true;
                                    }
                                }
                            }
                        });
        var items = new ArrayList<Map<String, Object>>();
        for (var match : matches) {
            items.add(
                    Map.of("path", match.path(), "line", match.line(), "content", match.content()));
        }
        return bounded("matches", items, truncated[0], scanned);
    }

    @Tool(name = "glob", description = "按相对路径匹配已授权文件，支持*/?/**，不递归目录软链接，返回排序后的路径和不完整状态")
    public String glob(
            @ToolParam(description = "目录或文件路径") String path,
            @ToolParam(description = "/分隔的相对模式，仅支持*/?/**") String pattern,
            @ToolParam(description = "最大结果数，默认200", required = false) Integer limit,
            ToolContext context) {
        var scope = scope(context);
        int cap = limit(limit);
        String[] parts = pattern(pattern);
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
        var paths = new TreeSet<String>();
        boolean[] truncated = {false};
        var scanned =
                scanner.scan(
                        path,
                        scope,
                        (relative, version) -> {
                            if (matches(parts, relative, scope)) {
                                paths.add(relative);
                                if (paths.size() > cap) {
                                    paths.pollLast();
                                    truncated[0] = true;
                                }
                            }
                        });
        return bounded("paths", new ArrayList<>(paths), truncated[0], scanned);
    }

    private String[] pattern(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 1000
                || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*")
                || value.indexOf(0) >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0
                || value.indexOf('{') >= 0
                || value.indexOf('}') >= 0) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (part.equals("..") || part.isEmpty()) {
                throw new FileToolException("INVALID_ARGUMENT");
            }
        }
        return parts;
    }

    /** 动态规划避免用户模式触发正则回溯；双星仅跨目录组件。 */
    private boolean matches(String[] pattern, String path, ToolExecutionScope scope) {
        String[] parts = path.split("/", -1);
        boolean[] before = new boolean[parts.length + 1];
        before[0] = true;
        for (String token : pattern) {
            scope.checkpoint();
            boolean[] next = new boolean[parts.length + 1];
            if (token.equals("**")) {
                next[0] = before[0];
                for (int j = 1; j <= parts.length; j++) {
                    next[j] = before[j] || next[j - 1];
                }
            } else {
                for (int j = 1; j <= parts.length; j++) {
                    next[j] = before[j - 1] && segment(token, parts[j - 1], scope);
                }
            }
            before = next;
        }
        return before[parts.length];
    }

    private boolean segment(String pattern, String value, ToolExecutionScope scope) {
        boolean[] before = new boolean[value.length() + 1];
        before[0] = true;
        for (int i = 0; i < pattern.length(); i++) {
            scope.checkpoint();
            char token = pattern.charAt(i);
            boolean[] next = new boolean[value.length() + 1];
            next[0] = token == '*' && before[0];
            for (int j = 1; j <= value.length(); j++) {
                next[j] =
                        token == '*'
                                ? before[j] || next[j - 1]
                                : before[j - 1] && (token == '?' || token == value.charAt(j - 1));
            }
            before = next;
        }
        return before[value.length()];
    }

    private String bounded(
            String key, List<?> entries, boolean truncated, BoundedFileScanner.Result scanned) {
        var result = new LinkedHashMap<String, Object>();
        result.put(key, entries);
        result.put("truncated", truncated);
        result.put("incomplete", scanned.incomplete());
        result.put("skipped", scanned.skipped());
        while (JSON.writeValueAsString(result).length() > policy.properties().maxResultChars()) {
            if (entries.isEmpty()) {
                throw new FileToolException("UNAVAILABLE：结果预算不足");
            }
            entries.removeLast();
            result.put("truncated", true);
        }
        return JSON.writeValueAsString(result);
    }
}
