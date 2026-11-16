package com.agentos.core.turn;

import com.agentos.core.session.TurnStore;
import java.util.Objects;

/** 恢复入口只协调存储端原子清理，不在进程内猜测其他实例的存活状态。 */
public final class TurnReaper {
    private final TurnStore turns;

    public TurnReaper(TurnStore turns) {
        this.turns = Objects.requireNonNull(turns);
    }

    public int recover() {
        return turns.reapInterrupted();
    }
}
