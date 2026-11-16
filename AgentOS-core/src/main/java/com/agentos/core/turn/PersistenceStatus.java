package com.agentos.core.turn;

/** 轮次收尾的存储确认标记；应与 TurnResult.status 一起判断，不能互相代替。 */
public enum PersistenceStatus {
    /** 存储端口确认收尾完成。 */
    CONFIRMED,
    /** 未得到存储确认，不应宣称轮次状态已可靠保存。 */
    UNCONFIRMED
}
