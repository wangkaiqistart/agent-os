package com.agentos.storage.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将输入转成可交给 Hibernate JSON 序列化的最小表示，不是 JSON 解析器或脱敏器。
 *
 * <p>Map 只做浅拷贝；其他非空对象转为字符串。因此传入一段 JSON 文本时，保存的是 JSON
 * 字符串值，并不会解析成嵌套对象，也不会检验它是否是合法 JSON 文本。
 */
public final class MessageCodec {
    private MessageCodec() {}

    /** 保留 null；拷贝 Map 顶层以避免调用方后续增删顶层键直接影响该副本。 */
    public static Object input(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(map);
        }
        return String.valueOf(value);
    }
}
