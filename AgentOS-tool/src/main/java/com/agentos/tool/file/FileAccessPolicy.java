package com.agentos.tool.file;

import com.agentos.core.tool.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 按真实路径组件授权。
 *
 * <p>工作区内可读也可写（写入是否需要用户确认由沙箱判定）；工作区外只有配置的读/写根可访问，
 * 其中写根视为预先授权，不再询问。
 */
public final class FileAccessPolicy {
    public static final String TEMP_PREFIX = ".agentos-write-";
    private final Path workspace;
    private final FileToolProperties properties;
    private final List<Path> reads;
    private final List<Path> writes;
    // 工作区真实路径；工作区目录尚不存在时为空，此时只按配置的根授权。
    private final List<Path> workspaceRoot;
    private final Path lockRoot;

    public FileAccessPolicy(Path workspace, FileToolProperties properties) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.properties = properties;
        reads = canonicalRoots(properties.readAllowedPaths());
        writes = canonicalRoots(properties.writeAllowedPaths());
        workspaceRoot = canonicalWorkspace(this.workspace);
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

    private static List<Path> canonicalWorkspace(Path workspace) {
        try {
            Path real = workspace.toRealPath();
            return Files.isDirectory(real) ? List.of(real) : List.of();
        } catch (IOException missing) {
            return List.of();
        }
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
                || (workspaceRoot.stream().noneMatch(canonical::startsWith)
                        && (write ? writes : reads).stream().noneMatch(canonical::startsWith))) {
            throw new SandboxViolationException("DENIED：文件目标未授权（只能访问工作区或配置的目录）");
        }
    }

    /** 写入目标是否在配置的写根内（预先授权，不需要用户确认）。 */
    public boolean preApprovedWrite(Path canonical) {
        return !protectedPath(canonical) && writes.stream().anyMatch(canonical::startsWith);
    }

    /** 工作区内的真实路径，工作区不存在时为空。 */
    public java.util.Optional<Path> workspaceRealPath() {
        return workspaceRoot.stream().findFirst();
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
