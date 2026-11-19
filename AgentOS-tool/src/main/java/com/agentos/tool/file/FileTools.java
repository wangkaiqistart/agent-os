package com.agentos.tool.file;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/** 仅操作明确授权的本地目标；提交不读取旧正文，不隐式创建业务目录。 */
public final class FileTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FileAccessPolicy policy;
    private final FileTargetResolver resolver;
    private final FileWriteCoordinator coordinator;
    private final AtomicFileCommitter committer;
    private final Sandbox sandbox;
    private final com.agentos.tool.sandbox.ToolApprovalGate gate;

    public FileTools(FileAccessPolicy policy) {
        this(policy, new com.agentos.tool.sandbox.WhitelistSandbox(List.of(), policy));
    }

    public FileTools(FileAccessPolicy policy, Sandbox sandbox) {
        this(policy, sandbox, com.agentos.tool.sandbox.ToolApprovalGate.of(sandbox));
    }

    public FileTools(
            FileAccessPolicy policy,
            Sandbox sandbox,
            com.agentos.tool.sandbox.ToolApprovalGate gate) {
        this.policy = policy;
        this.sandbox = sandbox;
        this.gate = gate;
        resolver = new FileTargetResolver(policy);
        coordinator = new FileWriteCoordinator(policy);
        committer = new AtomicFileCommitter(resolver);
    }

    private ToolExecutionScope scope(ToolContext context) {
        if (context == null
                || !(context.getContext().get(ToolContextKeys.TOOL_SCOPE)
                        instanceof ToolExecutionScope scope)) {
            throw new IllegalStateException("文件工具需要显式scope");
        }
        scope.checkpoint();
        return scope;
    }

    @Tool(name = "read_file", description = "读取工作区（或部署者配置的目录）内的UTF-8普通文件，无需用户确认；过大或非法编码会拒绝")
    public String read_file(
            @ToolParam(description = "工作区相对路径，或已配置目录内的绝对路径") String path, ToolContext context) {
        var scope = scope(context);
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
        var version = resolver.resolve(path, false);
        String content = readText(version, scope);
        var result = new LinkedHashMap<String, Object>();
        result.put("path", version.safePath());
        result.put("content", content);
        result.put("truncated", false);
        return boundedText(result, "content");
    }

    /** 有界读取至cap+1，严格解码；搜索和编辑复用相同文件语义。 */
    public String readText(FileTargetVersion version, ToolExecutionScope scope) {
        scope.checkpoint();
        policy.enforce(version.target(), false);
        resolver.revalidate(version, false);
        if (!version.regular()) {
            throw new FileToolException("UNSUPPORTED_TYPE");
        }
        if (version.size() > policy.properties().maxFileBytes()) {
            throw new FileToolException("TOO_LARGE");
        }
        try (var channel =
                FileChannel.open(
                        version.target(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var registration =
                    scope.register(deadline -> {
                        channel.close();
                        return !channel.isOpen();
                    });
            try {
                var bytes =
                        new ByteArrayOutputStream(
                                Math.min(policy.properties().maxFileBytes(), 8192));
                var buffer = ByteBuffer.allocate(8192);
                for (; ; ) {
                    scope.checkpoint();
                    buffer.clear();
                    buffer.limit(
                            Math.min(
                                    buffer.capacity(),
                                    policy.properties().maxFileBytes() + 1 - bytes.size()));
                    int n = channel.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    bytes.write(buffer.array(), 0, n);
                    if (bytes.size() > policy.properties().maxFileBytes()) {
                        throw new FileToolException("TOO_LARGE");
                    }
                }
                scope.checkpoint();
                resolver.revalidate(version, false);
                String content =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                                .toString();
                // NUL 可通过 UTF-8 解码，但属于本期不支持的二进制内容；读、编辑和搜索一致拒绝。
                if (content.indexOf(0) >= 0) {
                    throw new FileToolException("UNSUPPORTED_TYPE");
                }
                scope.checkpoint();
                return content;
            } finally {
                registration.close();
            }
        } catch (CharacterCodingException invalid) {
            throw new FileToolException("UNSUPPORTED_ENCODING");
        } catch (IOException failure) {
            scope.checkpoint();
            throw new FileToolException("UNAVAILABLE：文件读取失败");
        }
    }

    @Tool(
            name = "write_file",
            description =
                    "原子写入工作区内的文件，执行前会请求用户确认；默认仅创建，覆盖已有文件必须显式overwrite=true，"
                            + "不创建父目录。用户拒绝时不要重复同样的写入")
    public String write_file(
            @ToolParam(description = "待写文件路径") String path,
            @ToolParam(description = "UTF-8正文，可为空") String content,
            @ToolParam(description = "是否明确覆盖，默认false", required = false) Boolean overwrite,
            ToolContext context) {
        var scope = scope(context);
        if (content == null) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        byte[] bytes;
        try {
            var encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(content));
            if (encoded.remaining() > policy.properties().maxFileBytes()) {
                throw new FileToolException("TOO_LARGE");
            }
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException invalid) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        var planned = resolver.resolve(path, true);
        if (planned.exists() && !Boolean.TRUE.equals(overwrite)) {
            // 注定失败的写入不打扰用户确认。
            throw new FileToolException("CONFLICT：目标已存在");
        }
        gate.enforce(
                new SandboxAction(
                        ActionType.FILE_WRITE,
                        path,
                        List.of(
                                "方式  " + (planned.exists() ? "覆盖已有文件" : "新建"),
                                "大小  " + bytes.length + " 字节")),
                context);
        var beforeLock = resolver.resolve(path, true);
        AtomicFileCommitter.Result committed = null;
        try (var lease = coordinator.acquire(beforeLock.target(), scope)) {
            var version = resolver.resolve(path, true);
            if (!version.target().equals(beforeLock.target())) {
                throw new FileToolException("CONFLICT");
            }
            committed = committer.commit(version, bytes, Boolean.TRUE.equals(overwrite), scope);
        } catch (FileToolException cleanup) {
            if (committed == null || !committed.committed()) {
                throw cleanup;
            }
            committed =
                    new AtomicFileCommitter.Result(
                            committed.bytesWritten(), true, "CLEANUP_UNCONFIRMED");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("path", beforeLock.safePath());
        result.put("bytesWritten", committed.bytesWritten());
        result.put("committed", committed.committed());
        if (committed.cleanupWarning() != null) {
            result.put("cleanupWarning", committed.cleanupWarning());
        }
        return boundedText(result, "path");
    }

    @Tool(name = "list_dir", description = "列出工作区（或部署者配置的目录）内某个目录的直接子项，不递归；达到条目或扫描上限会标明不完整")
    public String list_dir(
            @ToolParam(description = "目录路径") String path,
            @ToolParam(description = "返回条数，默认200，可按部署限制收紧", required = false) Integer limit,
            ToolContext context) {
        var scope = scope(context);
        int cap = limit == null ? policy.properties().maxResults() : limit;
        if (cap < 1 || cap > policy.properties().maxResults()) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
        var version = resolver.resolve(path, false);
        if (!version.directory()) {
            throw new FileToolException("UNSUPPORTED_TYPE");
        }
        var entries = new ArrayList<Map<String, String>>();
        var skipped = new LinkedHashMap<String, Integer>();
        boolean incomplete = false;
        try (var stream = Files.newDirectoryStream(version.target())) {
            int scanned = 0;
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                scope.checkpoint();
                if (scanned >= policy.properties().maxScanEntries()) {
                    incomplete = true;
                    break;
                }
                Path entry = iterator.next();
                scanned++;
                if (policy.protectedPath(entry)) {
                    continue;
                }
                try {
                    resolver.resolve(entry.toString(), false);
                    var attrs =
                            Files.readAttributes(
                                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    String type =
                            attrs.isSymbolicLink()
                                    ? "symlink"
                                    : attrs.isRegularFile()
                                            ? "file"
                                            : attrs.isDirectory() ? "directory" : "other";
                    var entryName = entry.getFileName();
                    if (entryName == null) {
                        throw new FileToolException("UNAVAILABLE");
                    }
                    entries.add(
                            Map.of(
                                    "path",
                                    policy.safePath(entry),
                                    "name",
                                    entryName.toString(),
                                    "type",
                                    type));
                } catch (SandboxViolationException denied) {
                    skipped.merge("denied", 1, Integer::sum);
                    incomplete = true;
                } catch (IOException | FileToolException unavailable) {
                    skipped.merge("unreadable", 1, Integer::sum);
                    incomplete = true;
                }
            }
            scope.checkpoint();
            resolver.revalidate(version, false);
        } catch (IOException | java.nio.file.DirectoryIteratorException failure) {
            scope.checkpoint();
            throw new FileToolException("UNAVAILABLE：目录读取失败");
        }
        entries.sort(Comparator.comparing(item -> item.get("name")));
        boolean truncated = entries.size() > cap;
        if (truncated) {
            entries.subList(cap, entries.size()).clear();
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("entries", entries);
        result.put("truncated", truncated);
        result.put("incomplete", incomplete);
        result.put("skipped", skipped);
        while (JSON.writeValueAsString(result).length() > policy.properties().maxResultChars()
                && !entries.isEmpty()) {
            entries.removeLast();
            result.put("truncated", true);
        }
        return JSON.writeValueAsString(result);
    }

    private String boundedText(LinkedHashMap<String, Object> result, String field) {
        String encoded = JSON.writeValueAsString(result);
        while (encoded.length() > policy.properties().maxResultChars()) {
            String value = (String) result.get(field);
            if (value.isEmpty()) {
                throw new FileToolException("UNAVAILABLE：结果预算不足");
            }
            int length = value.length() / 2;
            if (length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) {
                length--;
            }
            result.put(field, value.substring(0, length));
            result.put("truncated", true);
            encoded = JSON.writeValueAsString(result);
        }
        return encoded;
    }
}
