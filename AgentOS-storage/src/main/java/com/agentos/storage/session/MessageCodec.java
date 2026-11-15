package com.agentos.storage.session;

import java.util.LinkedHashMap;
import java.util.Map;

/** jsonb 字段的最小安全编码器；非法 JSON 参数作为字符串值保存。 */
public final class MessageCodec {
    private MessageCodec() {}

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
