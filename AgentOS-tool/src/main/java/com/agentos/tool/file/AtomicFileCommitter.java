package com.agentos.tool.file;

import com.agentos.core.tool.ToolExecutionScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/** 完整临时文件落盘后才发布；原子能力不支持时安全失败，无copy回退。调用方必须持协作锁。 */
public final class AtomicFileCommitter {
    public enum Stage {
        TEMP_CREATED,
        WRITTEN,
        FORCED,
        REVALIDATED,
        PUBLISH,
        CLEANUP
    }

    @FunctionalInterface
    public interface FaultHook {
        void at(Stage stage, Path temp) throws IOException;
    }

    public record Result(int bytesWritten, boolean committed, String cleanupWarning) {}

    private final FileTargetResolver resolver;
    private final FaultHook hook;

    public AtomicFileCommitter(FileTargetResolver resolver) {
        this(resolver, (stage, temp) -> {});
    }

    public AtomicFileCommitter(FileTargetResolver resolver, FaultHook hook) {
        this.resolver = Objects.requireNonNull(resolver);
        this.hook = Objects.requireNonNull(hook);
    }

    public Result commit(
            FileTargetVersion version, byte[] bytes, boolean overwrite, ToolExecutionScope scope) {
        return commit(version, bytes, overwrite, scope, () -> {});
    }

    public Result commit(
            FileTargetVersion version,
            byte[] bytes,
            boolean overwrite,
            ToolExecutionScope scope,
            Runnable verifyContent) {
        if (bytes.length > 1048576) {
            throw new FileToolException("TOO_LARGE");
        }
        if (version.exists() && !overwrite) {
            throw new FileToolException("CONFLICT：目标已存在");
        }
        if (version.exists() && !version.regular()) {
            throw new FileToolException("UNSUPPORTED_TYPE");
        }
        Path temp = null;
        boolean committed = false;
        String cleanupWarning = null;
        try {
            scope.checkpoint();
            resolver.revalidate(version, true);
            temp =
                    Files.createTempFile(
                            version.parent(),
                            FileAccessPolicy.TEMP_PREFIX,
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            hook.at(Stage.TEMP_CREATED, temp);
            try (var channel =
                    FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    scope.checkpoint();
                    channel.write(buffer);
                }
                hook.at(Stage.WRITTEN, temp);
                scope.checkpoint();
                channel.force(true);
                hook.at(Stage.FORCED, temp);
            }
            scope.checkpoint();
            resolver.revalidate(version, true);
            hook.at(Stage.REVALIDATED, temp);
            hook.at(Stage.PUBLISH, temp);
            scope.checkpoint();
            resolver.revalidate(version, true);
            verifyContent.run();
            scope.checkpoint();
            scope.possiblyStarted();
            if (overwrite) {
                Files.move(
                        temp,
                        version.target(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(version.target(), temp);
            }
            committed = true;
            scope.completed();
        } catch (java.nio.file.FileAlreadyExistsException conflict) {
            throw new FileToolException("CONFLICT：目标已存在");
        } catch (IOException | UnsupportedOperationException failure) {
            throw new FileToolException("UNAVAILABLE：文件原子提交失败");
        } finally {
            if (temp != null) {
                try {
                    hook.at(Stage.CLEANUP, temp);
                    Files.deleteIfExists(temp);
                } catch (IOException failure) {
                    cleanupWarning = "CLEANUP_UNCONFIRMED";
                }
            }
        }
        return new Result(bytes.length, committed, cleanupWarning);
    }
}
