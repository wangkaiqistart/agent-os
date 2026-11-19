package com.agentos.tool.file;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/** 唯一字面匹配、双权限及协作锁；完整准备后复核版本和原文摘要再原子替换。 */
public final class FileEditTools {
    private final FileAccessPolicy policy;
    private final Sandbox sandbox;
    private final FileTargetResolver resolver;
    private final FileWriteCoordinator coordinator;
    private final AtomicFileCommitter committer;
    private final FileTools reader;

    public FileEditTools(FileAccessPolicy policy, Sandbox sandbox) {
        this(policy, sandbox, (stage, temp) -> {});
    }

    public FileEditTools(
            FileAccessPolicy policy, Sandbox sandbox, AtomicFileCommitter.FaultHook hook) {
        this.policy = policy;
        this.sandbox = sandbox;
        resolver = new FileTargetResolver(policy);
        coordinator = new FileWriteCoordinator(policy);
        committer = new AtomicFileCommitter(resolver, hook);
        reader = new FileTools(policy, sandbox);
    }

    @Tool(name = "edit_file", description = "在同时有读写权限的UTF-8文件中精确替换唯一匹配，空白旧文本合法，多匹配拒绝")
    public String edit_file(
            @ToolParam(description = "待编辑路径") String path,
            @ToolParam(description = "长度大于0的旧文本，允许纯空白") String oldText,
            @ToolParam(description = "新文本，可为空") String newText,
            ToolContext context) {
        var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        scope.checkpoint();
        if (oldText == null || oldText.isEmpty() || newText == null) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        if (newText.length() > policy.properties().maxFileBytes()) {
            throw new FileToolException("TOO_LARGE");
        }
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
        sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
        var before = resolver.resolve(path, true);
        AtomicFileCommitter.Result committed = null;
        try (var lease = coordinator.acquire(before.target(), scope)) {
            var version = resolver.resolve(path, true);
            if (!before.target().equals(version.target())) {
                throw new FileToolException("CONFLICT");
            }
            String original = reader.readText(version, scope);
            int first = original.indexOf(oldText);
            if (first < 0 || original.indexOf(oldText, first + 1) >= 0) {
                throw new FileToolException("CONFLICT：旧文本必须恰好匹配一次");
            }
            String changed =
                    original.substring(0, first)
                            + newText
                            + original.substring(first + oldText.length());
            byte[] bytes = encode(changed);
            if (bytes.length > policy.properties().maxFileBytes()) {
                throw new FileToolException("TOO_LARGE");
            }
            byte[] expected = digest(encode(original));
            committed =
                    committer.commit(
                            version,
                            bytes,
                            true,
                            scope,
                            () -> {
                                sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
                                byte[] current = digest(encode(reader.readText(version, scope)));
                                if (!MessageDigest.isEqual(expected, current)) {
                                    throw new FileToolException("CONFLICT：原文摘要已变化");
                                }
                            });
        } catch (FileToolException cleanup) {
            if (committed == null || !committed.committed()) {
                throw cleanup;
            }
            committed =
                    new AtomicFileCommitter.Result(
                            committed.bytesWritten(), true, "CLEANUP_UNCONFIRMED");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("path", before.safePath());
        result.put("replacements", 1);
        result.put("bytesWritten", committed.bytesWritten());
        result.put("committed", true);
        if (committed.cleanupWarning() != null) {
            result.put("cleanupWarning", committed.cleanupWarning());
        }
        var json = JsonMapper.builder().build();
        while (json.writeValueAsString(result).length() > policy.properties().maxResultChars()) {
            String value = (String) result.get("path");
            if (value.isEmpty()) {
                throw new FileToolException("UNAVAILABLE：结果预算不足");
            }
            result.put("path", value.substring(0, value.length() / 2));
            result.put("truncated", true);
        }
        return json.writeValueAsString(result);
    }

    private static byte[] encode(String value) {
        try {
            ByteBuffer bytes =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            var result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new FileToolException("UNSUPPORTED_ENCODING");
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("摘要算法不可用");
        }
    }
}
