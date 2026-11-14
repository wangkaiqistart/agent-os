package com.agentos.provider.support;

import tools.jackson.databind.json.JsonMapper;

/**
 * 聊天接口流式响应的 SSE 分片，供离线协议测试复用。
 *
 * <p>每个方法返回一个完整的 {@code data: ...} 事件，可以直接放进 {@code StubResponse.sse(...)}。
 * 一次正常的流以角色分片开头，以结束分片、用量分片和 {@link #DONE} 收尾。
 */
public final class SseChunks {

    /** 流结束标记。 */
    public static final String DONE = "data: [DONE]\n\n";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String HEAD =
            "{\"id\":\"chatcmpl-s1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"actual-model\",";

    private SseChunks() {}

    /** 第一个分片：只带角色，没有文本。 */
    public static String role() {
        return event(choice("{\"role\":\"assistant\",\"content\":\"\"}", "null"));
    }

    /** 一段文本增量。 */
    public static String text(String content) {
        return event(choice("{\"content\":" + JSON.writeValueAsString(content) + "}", "null"));
    }

    /** 工具调用的第一个分片：带 ID 和名称，参数从空串开始。 */
    public static String toolCallStart(int index, String id, String name) {
        String delta =
                "{\"tool_calls\":[{\"index\":"
                        + index
                        + ",\"id\":"
                        + JSON.writeValueAsString(id)
                        + ",\"type\":\"function\",\"function\":{\"name\":"
                        + JSON.writeValueAsString(name)
                        + ",\"arguments\":\"\"}}]}";
        return event(choice(delta, "null"));
    }

    /** 工具调用参数的一个片段，片段拼起来才是完整的 JSON。 */
    public static String toolCallArguments(int index, String fragment) {
        String delta =
                "{\"tool_calls\":[{\"index\":"
                        + index
                        + ",\"function\":{\"arguments\":"
                        + JSON.writeValueAsString(fragment)
                        + "}}]}";
        return event(choice(delta, "null"));
    }

    /** 结束分片：没有内容，只有结束原因。 */
    public static String finish(String reason) {
        return event(choice("{}", JSON.writeValueAsString(reason)));
    }

    /** 用量分片：{@code stream_options.include_usage} 打开时，服务端在最后发一个 choices 为空的分片。 */
    public static String usage(int promptTokens, int completionTokens) {
        return "data: "
                + HEAD
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":"
                + promptTokens
                + ",\"completion_tokens\":"
                + completionTokens
                + ",\"total_tokens\":"
                + (promptTokens + completionTokens)
                + "}}\n\n";
    }

    /** 用量分片，并带上缓存命中的输入 token 数（{@code prompt_tokens_details.cached_tokens}）。 */
    public static String usage(int promptTokens, int completionTokens, int cachedTokens) {
        return "data: "
                + HEAD
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":"
                + promptTokens
                + ",\"completion_tokens\":"
                + completionTokens
                + ",\"total_tokens\":"
                + (promptTokens + completionTokens)
                + ",\"prompt_tokens_details\":{\"cached_tokens\":"
                + cachedTokens
                + "}}}\n\n";
    }

    private static String choice(String delta, String finishReason) {
        return "\"choices\":[{\"index\":0,\"delta\":"
                + delta
                + ",\"finish_reason\":"
                + finishReason
                + "}]";
    }

    private static String event(String choices) {
        return "data: " + HEAD + choices + "}\n\n";
    }
}
