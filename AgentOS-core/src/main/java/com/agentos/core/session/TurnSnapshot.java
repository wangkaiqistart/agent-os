package com.agentos.core.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 轮次创建或查询后返回给执行层的快照，跨线程传递时不携带数据库连接。
 *
 * @param turnId 当前轮次 UUID
 * @param sessionId 该轮所属会话 UUID
 * @param sessionKey 定位会话的自然键
 * @param owner 本轮申请时的租约身份，续租与释放时用它核对所有权
 * @param deadline 创建时记录的绝对截止时间
 * @param status 读取时的轮次状态；后续存储更新不会原地改变这个对象
 */
public record TurnSnapshot(
        UUID turnId,
        UUID sessionId,
        SessionKey sessionKey,
        String owner,
        Instant deadline,
        String status) {}
