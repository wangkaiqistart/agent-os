package com.agentos.storage;

import com.agentos.core.turn.TurnReaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** chat 启动时运行一次；不是调度器，强杀后的恢复仍需下一次启动或会话接管。 */
public final class TurnReaperRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TurnReaperRunner.class);
    private final TurnReaper reaper;

    public TurnReaperRunner(TurnReaper reaper) {
        this.reaper = reaper;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        // 恢复失败让启动失败，不能把数据库不可用伪装为“已恢复”。
        log.info("启动回收完成，处理记录数={}", reaper.recover());
    }
}
