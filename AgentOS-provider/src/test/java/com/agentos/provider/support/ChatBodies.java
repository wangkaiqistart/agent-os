package com.agentos.provider.support;

/**
 * 聊天接口的固定响应体，供离线协议测试复用。
 *
 * <p>字段取值刻意写得很小，只保留被测逻辑关心的部分：文本、工具调用、结束原因、用量。
 */
public final class ChatBodies {

    /** 正常的文本响应，带完整用量。 */
    public static final String OK =
            """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    /** 只有两个工具调用、没有文本的响应；用来验证顺序、ID、名称和参数原样返回。 */
    public static final String TWO_TOOL_CALLS =
            """
            {"id":"chatcmpl-2","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"tool_calls",
               "message":{"role":"assistant","content":null,"tool_calls":[
                 {"id":"call_a","type":"function",
                  "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Beijing\\"}"}},
                 {"id":"call_b","type":"function",
                  "function":{"name":"get_time","arguments":"{\\"zone\\":\\"UTC\\"}"}}]}}],
             "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
            """;

    /** 没有任何候选结果的响应，属于协议异常。 */
    public static final String EMPTY_CHOICES =
            """
            {"id":"chatcmpl-3","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[],
             "usage":{"prompt_tokens":3,"completion_tokens":0,"total_tokens":3}}
            """;

    private ChatBodies() {}
}
