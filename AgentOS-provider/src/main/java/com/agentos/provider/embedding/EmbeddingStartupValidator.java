package com.agentos.provider.embedding;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * 应用就绪后在后台校验向量化，失败时按退避重新校验，应用关闭时停止。
 *
 * <p>校验放在 {@link ApplicationReadyEvent} 之后、虚拟线程上，所以启动耗时不受向量化服务快慢影响（SC-020）。
 * 同步校验（{@code startup-check=sync}）已经在创建服务时做完第一次；它失败后也由本类接手重新校验。
 *
 * <p>只有"不可用且原因不是鉴权失败"才会重新校验：鉴权失败的 key 来自环境变量，运行期间不会变。
 * 配置错误（维度不一致）不重新校验，修正配置并重启才恢复。
 */
public final class EmbeddingStartupValidator
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** 关闭时等后台线程退出的上限。 */
    private static final long JOIN_MILLIS = 5_000;

    private final DefaultEmbeddingService service;

    private final EmbeddingBackoff backoff;

    private final AtomicBoolean started = new AtomicBoolean();

    private volatile boolean closed;

    private volatile Thread worker;

    public EmbeddingStartupValidator(DefaultEmbeddingService service, EmbeddingBackoff backoff) {
        this.service = Objects.requireNonNull(service, "service");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 就绪事件在父子上下文里可能重复到达，只启动一次。
        if (!(service.pending() || service.needsRevalidation())
                || !started.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofVirtual().name("embedding-validator").start(this::run);
    }

    private void run() {
        int failures = service.pending() ? 0 : 1;
        if (service.pending()) {
            service.validate();
        }
        while (!closed && service.needsRevalidation()) {
            failures++;
            try {
                Thread.sleep(backoff.delay(failures));
            } catch (InterruptedException interrupted) {
                // 应用在关闭：恢复标志并退出循环。
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) {
                return;
            }
            service.validate();
        }
    }

    /** 后台线程是否还在运行；测试用它确认关闭后重新校验确实停了。 */
    boolean isRunning() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    @Override
    public void destroy() {
        closed = true;
        Thread current = worker;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
