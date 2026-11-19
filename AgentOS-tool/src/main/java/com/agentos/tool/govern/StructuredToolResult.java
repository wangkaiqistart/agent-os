package com.agentos.tool.govern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 最后一道字符预算：JSON保持可解析和状态字段，普通文本保留原有截断标记。 */
final class StructuredToolResult {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StructuredToolResult() {}

    static String limit(String safe, int cap, int original) {
        Object value;
        try {
            value = JSON.readValue(safe, Object.class);
        } catch (RuntimeException ignored) {
            return text(safe, cap, original);
        }
        if (!(value instanceof Map<?, ?>)) {
            return text(safe, cap, original);
        }
        for (int length = Math.min(safe.length(), cap);
                length >= 0;
                length = length <= 1 ? -1 : length / 2) {
            var reduced = reduce(value, length);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) reduced;
            map.put("truncated", true);
            String result = JSON.writeValueAsString(map);
            if (result.length() <= cap) {
                return result;
            }
        }
        throw new IllegalStateException("工具结果预算不足以容纳结构及执行状态");
    }

    private static Object reduce(Object value, int length) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                // 这些短状态值描述提交/完整性，不能在裁剪正文时抹除。
                result.put(
                        name,
                        name.equals("status") || name.equals("completion")
                                ? item
                                : reduce(item, length));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            list.stream().limit(length).forEach(item -> result.add(reduce(item, length)));
            return result;
        }
        if (value instanceof String string && string.length() > length) {
            return string.substring(0, length);
        }
        return value;
    }

    private static String text(String value, int cap, int original) {
        String marker = "\n[结果已截断，原长 " + original + " 字符]";
        return cap <= marker.length()
                ? marker.substring(0, cap)
                : value.substring(0, Math.min(value.length(), cap - marker.length())) + marker;
    }
}
