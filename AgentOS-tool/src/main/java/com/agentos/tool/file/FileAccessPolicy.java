package com.agentos.tool.file;

import com.agentos.core.tool.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 按真实路径组件授权；工作根仅解析相对路径，不隐含授权。 */
public final class FileAccessPolicy {
    public static final String TEMP_PREFIX = ".agentos-write-";
    private final Path workspace;
    private final FileToolProperties properties;
    private final List<Path> reads;
    private final List<Path> writes;
    private final Path lockRoot;

    public FileAccessPolicy(Path workspace, FileToolProperties properties) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.properties = properties;
        reads = canonicalRoots(properties.readAllowedPaths());
        writes = canonicalRoots(properties.writeAllowedPaths());
        Path configured = resolve(properties.lockRoot());
        try {
            // 默认无权限的应用/查询不创建运行目录，现有祖先仍解析真实位置。
            var suffix = new ArrayList<Path>();
            Path ancestor = configured;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                suffix.addFirst(ancestor.getFileName());
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                throw new IOException();
            }
            Path canonical = ancestor.toRealPath();
            for (Path part : suffix) {
                canonical = canonical.resolve(part);
            }
            lockRoot = canonical;
            if (Files.isSymbolicLink(configured)) {
                throw new IOException();
            }
            if (reads.stream().anyMatch(root -> root.startsWith(lockRoot))
                    || writes.stream().anyMatch(root -> root.startsWith(lockRoot))) {
                throw new IOException();
            }
        } catch (IOException invalid) {
            throw new IllegalArgumentException("文件锁根配置不合法");
        }
    }

    private List<Path> canonicalRoots(List<Path> configured) {
        var result = new ArrayList<Path>();
        for (Path path : configured) {
            try {
                Path real = resolve(path).toRealPath();
                if (!Files.isDirectory(real)) {
                    throw new IOException();
                }
                if (!result.contains(real)) {
                    result.add(real);
                }
            } catch (IOException invalid) {
                throw new IllegalArgumentException("文件授权根必须是存在的目录");
            }
        }
        return List.copyOf(result);
    }

    private Path resolve(Path path) {
        return path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize();
    }

    public Path workspace() {
        return workspace;
    }

    public FileToolProperties properties() {
        return properties;
    }

    public Path lockRoot() {
        return lockRoot;
    }

    public void enforce(Path canonical, boolean write) {
        if (protectedPath(canonical)
                || (write ? writes : reads).stream().noneMatch(canonical::startsWith)) {
            throw new SandboxViolationException("DENIED：文件目标未授权");
        }
    }

    public boolean protectedPath(Path path) {
        if (path.startsWith(lockRoot)) {
            return true;
        }
        for (Path component : path) {
            if (component.toString().startsWith(TEMP_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public String safePath(Path path) {
        Path canonicalWorkspace;
        try {
            canonicalWorkspace = workspace.toRealPath();
        } catch (IOException missing) {
            canonicalWorkspace = workspace;
        }
        if (path.startsWith(canonicalWorkspace)) {
            String relative =
                    canonicalWorkspace
                            .relativize(path)
                            .toString()
                            .replace(path.getFileSystem().getSeparator(), "/");
            return relative.isEmpty() ? "." : relative;
        }
        var roots = new ArrayList<Path>(reads);
        for (Path write : writes) {
            if (!roots.contains(write)) {
                roots.add(write);
            }
        }
        for (int i = 0; i < roots.size(); i++) {
            if (path.startsWith(roots.get(i))) {
                String relative =
                        roots.get(i)
                                .relativize(path)
                                .toString()
                                .replace(path.getFileSystem().getSeparator(), "/");
                return "allowed-" + (i + 1) + (relative.isEmpty() ? "" : "/" + relative);
            }
        }
        throw new SandboxViolationException("DENIED：文件目标未授权");
    }

    public Object lockRootKey() {
        try {
            var attributes =
                    Files.readAttributes(
                            lockRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.fileKey() == null
                    || !lockRoot.toRealPath().equals(lockRoot)) {
                throw new IOException();
            }
            return attributes.fileKey();
        } catch (IOException invalid) {
            throw new FileToolException("UNAVAILABLE：文件锁根不可用");
        }
    }
}
