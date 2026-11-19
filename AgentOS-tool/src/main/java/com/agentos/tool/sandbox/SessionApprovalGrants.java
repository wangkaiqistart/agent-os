package com.agentos.tool.sandbox;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 用户选择"本会话不再询问"后的授权记录，按会话隔离，只存在于进程内存中。
 *
 * <p>CLI 与后续 HTTP 入口共用：同一 sessionId 内相同 grantKey 不再询问，换会话或重启进程后失效。
 * 最多保留 {@link #MAX_SESSIONS} 个会话，超出时淘汰最久未使用的会话。
 */
public final class SessionApprovalGrants {
    static final int MAX_SESSIONS = 1000;
    static final int MAX_GRANTS_PER_SESSION = 200;

    private final Map<UUID, Set<String>> grants =
            new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Set<String>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };

    public synchronized boolean contains(UUID sessionId, String grantKey) {
        if (sessionId == null) {
            return false;
        }
        var keys = grants.get(sessionId);
        return keys != null && keys.contains(grantKey);
    }

    public synchronized void grant(UUID sessionId, String grantKey) {
        Objects.requireNonNull(grantKey);
        if (sessionId == null) {
            return;
        }
        var keys = grants.computeIfAbsent(sessionId, ignored -> new HashSet<>());
        if (keys.size() < MAX_GRANTS_PER_SESSION) {
            keys.add(grantKey);
        }
    }
}
