package com.agentos.tool.file;

import com.agentos.core.tool.ToolExecutionScope;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/** 规范路径进程锁和稳定sidecar锁；所有协作写方使用相同锁根与算法。 */
public final class FileWriteCoordinator {
    private static final class Node {
        final ReentrantLock lock = new ReentrantLock();
        int references;
    }

    private static final ConcurrentHashMap<Path, Node> LOCKS = new ConcurrentHashMap<>();
    private final FileAccessPolicy policy;
    private final Object rootMonitor = new Object();

    public FileWriteCoordinator(FileAccessPolicy policy) {
        this.policy = policy;
    }

    public interface Lease extends AutoCloseable {
        void close();
    }

    public Lease acquire(Path path, ToolExecutionScope scope) {
        // 一次写入只锁定一个规范目标，多目标调用方必须先按Path顺序取得。
        Path target = path.toAbsolutePath().normalize();
        policy.enforce(target, true);
        scope.checkpoint();
        Node node =
                LOCKS.compute(
                        target,
                        (key, current) -> {
                            var value = current == null ? new Node() : current;
                            value.references++;
                            return value;
                        });
        boolean acquired = false;
        FileChannel channel = null;
        ToolExecutionScope.Registration cancellation = null;
        try {
            while (!(acquired =
                    node.lock.tryLock(
                            Math.min(scope.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(10)),
                            TimeUnit.NANOSECONDS))) {
                scope.checkpoint();
            }
            scope.checkpoint();
            synchronized (rootMonitor) {
                if (!Files.exists(policy.lockRoot(), LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(
                                policy.lockRoot(),
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
                    } catch (java.nio.file.FileAlreadyExistsException raced) {
                        /* 并发初始化者已建立，下面按身份验证。 */
                    }
                }
            }
            Object rootKey = policy.lockRootKey();
            Path sidecar = policy.lockRoot().resolve(lockName(target));
            channel =
                    FileChannel.open(
                            sidecar,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            FileChannel owned = channel;
            cancellation =
                    scope.register(deadline -> {
                        owned.close();
                        return !owned.isOpen();
                    });
            FileLock lock = null;
            while (lock == null) {
                scope.checkpoint();
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    /* 外部适配器同JVM持有锁，仍使用同一预算等待。 */
                }
                if (lock == null) {
                    LockSupport.parkNanos(
                            Math.min(scope.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(5)));
                }
            }
            if (!rootKey.equals(policy.lockRootKey())) {
                throw new FileToolException("CONFLICT：文件锁根已变化");
            }
            scope.checkpoint();
            FileLock held = lock;
            ToolExecutionScope.Registration registration = cancellation;
            var closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    registration.close();
                    try {
                        if (held.isValid()) {
                            held.release();
                        }
                        owned.close();
                    } catch (IOException failure) {
                        throw new FileToolException("CLEANUP_UNCONFIRMED：协作锁关闭未确认");
                    } finally {
                        node.lock.unlock();
                        releaseReference(target, node);
                    }
                }
            };
        } catch (IOException failure) {
            closeFailed(channel, cancellation, target, node, acquired);
            throw new FileToolException("UNAVAILABLE：文件协作锁不可用");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeFailed(channel, cancellation, target, node, acquired);
            scope.checkpoint();
            throw new FileToolException("TIMEOUT：文件锁等待已取消");
        } catch (RuntimeException failure) {
            closeFailed(channel, cancellation, target, node, acquired);
            throw failure;
        }
    }

    private static void closeFailed(
            FileChannel channel,
            ToolExecutionScope.Registration registration,
            Path target,
            Node node,
            boolean acquired) {
        if (registration != null) {
            registration.close();
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            /* 未取得业务执行许可，保留安全失败分类。 */
        } finally {
            if (acquired) {
                node.lock.unlock();
            }
            releaseReference(target, node);
        }
    }

    private static void releaseReference(Path target, Node node) {
        LOCKS.computeIfPresent(
                target,
                (key, current) -> {
                    if (current != node) {
                        throw new IllegalStateException("锁身份不一致");
                    }
                    current.references--;
                    return current.references == 0 ? null : current;
                });
    }

    public static String lockName(Path canonical) {
        try {
            return HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    canonical
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8)))
                    + ".lock";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("平台缺少SHA-256");
        }
    }
}
