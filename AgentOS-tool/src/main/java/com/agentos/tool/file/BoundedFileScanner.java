package com.agentos.tool.file;

import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolExecutionScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** 逐目录项消耗扫描预算，不递归目录软链接；跳过只保留安全计数。 */
public final class BoundedFileScanner {
    public record Result(boolean incomplete, Map<String, Integer> skipped) {}

    private final com.agentos.core.tool.Sandbox sandbox;
    private final FileAccessPolicy policy;
    private final FileTargetResolver resolver;

    public BoundedFileScanner(FileAccessPolicy policy, com.agentos.core.tool.Sandbox sandbox) {
        this.policy = policy;
        this.sandbox = sandbox;
        resolver = new FileTargetResolver(policy);
    }

    public Result scan(
            String input, ToolExecutionScope scope, BiConsumer<String, FileTargetVersion> visitor) {
        scope.checkpoint();
        sandbox.enforce(
                new com.agentos.core.tool.SandboxAction(
                        com.agentos.core.tool.ActionType.FILE_READ, input));
        var root = resolver.resolve(input, false);
        var skipped = new LinkedHashMap<String, Integer>();
        boolean incomplete = false;
        int scanned = 0;
        if (root.regular()) {
            try {
                Path name = root.target().getFileName();
                if (name == null) {
                    throw new FileToolException("UNSUPPORTED_TYPE");
                }
                visitor.accept(name.toString(), root);
                return new Result(false, Map.of());
            } catch (FileToolException unavailable) {
                skipped.merge(classify(unavailable), 1, Integer::sum);
                return new Result(true, Map.copyOf(skipped));
            }
        }
        if (!root.directory()) {
            throw new FileToolException("UNSUPPORTED_TYPE");
        }
        var pending = new ArrayDeque<Path>();
        pending.add(root.target());
        while (!pending.isEmpty()) {
            scope.checkpoint();
            Path directory = pending.removeFirst();
            try {
                sandbox.enforce(
                        new com.agentos.core.tool.SandboxAction(
                                com.agentos.core.tool.ActionType.FILE_READ, directory.toString()));
                var version = resolver.resolve(directory.toString(), false);
                if (!version.directory() || Files.isSymbolicLink(directory)) {
                    skipped.merge("unsupported_type", 1, Integer::sum);
                    incomplete = true;
                    continue;
                }
                try (var stream = Files.newDirectoryStream(version.target())) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        scope.checkpoint();
                        if (scanned >= policy.properties().maxScanEntries()) {
                            return new Result(true, Map.copyOf(skipped));
                        }
                        Path entry = iterator.next();
                        scanned++;
                        if (policy.protectedPath(entry)) {
                            continue;
                        }
                        try {
                            scope.checkpoint();
                            sandbox.enforce(
                                    new com.agentos.core.tool.SandboxAction(
                                            com.agentos.core.tool.ActionType.FILE_READ,
                                            entry.toString()));
                            var target = resolver.resolve(entry.toString(), false);
                            if (target.directory()) {
                                if (Files.isSymbolicLink(entry)) {
                                    skipped.merge("unsupported_type", 1, Integer::sum);
                                    incomplete = true;
                                } else {
                                    pending.addLast(entry);
                                }
                            } else if (target.regular()) {
                                String relative =
                                        root.target()
                                                .relativize(entry)
                                                .toString()
                                                .replace(entry.getFileSystem().getSeparator(), "/");
                                visitor.accept(relative, target);
                            } else {
                                skipped.merge("unsupported_type", 1, Integer::sum);
                                incomplete = true;
                            }
                        } catch (SandboxViolationException denied) {
                            skipped.merge("denied", 1, Integer::sum);
                            incomplete = true;
                        } catch (FileToolException unavailable) {
                            skipped.merge(classify(unavailable), 1, Integer::sum);
                            incomplete = true;
                        }
                    }
                }
                resolver.revalidate(version, false);
            } catch (SandboxViolationException denied) {
                skipped.merge("denied", 1, Integer::sum);
                incomplete = true;
            } catch (IOException
                    | java.nio.file.DirectoryIteratorException
                    | FileToolException unreadable) {
                skipped.merge("unreadable", 1, Integer::sum);
                incomplete = true;
            }
        }
        return new Result(incomplete, Map.copyOf(skipped));
    }

    private String classify(FileToolException failure) {
        return switch (failure.getMessage()) {
            case "UNSUPPORTED_ENCODING" -> "unsupported_encoding";
            case "TOO_LARGE" -> "too_large";
            case "UNSUPPORTED_TYPE" -> "unsupported_type";
            default -> "unreadable";
        };
    }
}
