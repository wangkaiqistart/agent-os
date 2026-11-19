package com.agentos.tool.file;

import com.agentos.core.tool.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** 解析最终目标并保存版本；不为写权限读取旧正文。 */
public final class FileTargetResolver {
    private final FileAccessPolicy policy;

    public FileTargetResolver(FileAccessPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    public FileTargetVersion resolve(String input, boolean write) {
        if (input == null || input.isBlank() || input.indexOf(0) >= 0) {
            throw new FileToolException("INVALID_ARGUMENT");
        }
        try {
            Path requested = Path.of(input);
            requested =
                    (requested.isAbsolute() ? requested : policy.workspace().resolve(requested))
                            .normalize();
            Path canonical;
            boolean exists;
            try {
                canonical = requested.toRealPath();
                exists = true;
            } catch (NoSuchFileException missing) {
                // 悬空链接不是可创建的新普通文件。
                Path requestedParent = requested.getParent();
                Path requestedName = requested.getFileName();
                if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)
                        || requestedParent == null
                        || requestedName == null) {
                    throw missing;
                }
                canonical = requestedParent.toRealPath().resolve(requestedName);
                exists = false;
            }
            policy.enforce(canonical, write);
            if (!write && !exists) {
                throw new NoSuchFileException("缺失");
            }
            Path parent = canonical.getParent();
            if (parent == null) {
                parent = canonical;
            }
            var parentAttributes =
                    Files.readAttributes(
                            parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!parentAttributes.isDirectory() || parentAttributes.fileKey() == null) {
                throw new IOException();
            }
            var attributes =
                    exists
                            ? Files.readAttributes(
                                    canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                            : null;
            if (attributes != null && attributes.fileKey() == null) {
                throw new IOException();
            }
            return new FileTargetVersion(
                    input,
                    canonical,
                    parent,
                    parentAttributes.fileKey(),
                    exists,
                    exists ? attributes.fileKey() : null,
                    exists ? attributes.size() : 0,
                    exists ? attributes.lastModifiedTime() : null,
                    exists && attributes.isRegularFile(),
                    exists && attributes.isDirectory(),
                    policy.safePath(canonical));
        } catch (IOException | java.nio.file.InvalidPathException invalid) {
            throw new FileToolException("UNAVAILABLE：文件目标不可用");
        }
    }

    public void revalidate(FileTargetVersion expected, boolean write) {
        FileTargetVersion actual;
        try {
            actual = resolve(expected.input(), write);
        } catch (FileToolException | SandboxViolationException changed) {
            throw new FileToolException("CONFLICT：文件目标已变化");
        }
        if (!expected.target().equals(actual.target())
                || !expected.parent().equals(actual.parent())
                || !expected.parentKey().equals(actual.parentKey())
                || expected.exists() != actual.exists()
                || !Objects.equals(expected.fileKey(), actual.fileKey())
                || expected.size() != actual.size()
                || !Objects.equals(expected.modified(), actual.modified())
                || expected.regular() != actual.regular()
                || expected.directory() != actual.directory()) {
            throw new FileToolException("CONFLICT：文件目标已变化");
        }
    }
}
