package com.agentos.provider.support;

/**
 * 向量化接口的固定响应体，供离线协议测试复用。
 *
 * <p>向量的第一个分量写成"全局序号"，其余分量填 0.5：测试靠它判断分批后结果顺序有没有乱。
 */
public final class EmbeddingBodies {

    /** 服务端欠费时的错误体：错误码带 Arrearage，HTTP 状态码是 400。 */
    public static final String ARREARS =
            "{\"error\":{\"message\":\"Access denied, account is in arrears\","
                    + "\"type\":\"Arrearage\",\"code\":\"Arrearage\"}}";

    /** 密钥错误。 */
    public static final String UNAUTHORIZED =
            "{\"error\":{\"message\":\"bad key\",\"type\":\"invalid_request_error\","
                    + "\"code\":\"invalid_api_key\"}}";

    /** 其他参数错误。 */
    public static final String BAD_REQUEST =
            "{\"error\":{\"message\":\"bad input\",\"type\":\"invalid_request_error\","
                    + "\"code\":\"invalid_input\"}}";

    private EmbeddingBodies() {}

    /**
     * 返回 {@code count} 个 {@code dimensions} 维向量，第 i 个向量的第一个分量是 {@code startIndex + i}。
     */
    public static String vectors(int count, int dimensions, int startIndex) {
        return vectors(count, dimensions, startIndex, 0.5f);
    }

    /** 同上，但可以指定其余分量的取值，用来在日志里找向量值有没有泄露。 */
    public static String vectors(int count, int dimensions, int startIndex, float fill) {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                data.append(',');
            }
            data.append("{\"object\":\"embedding\",\"index\":")
                    .append(i)
                    .append(",\"embedding\":[");
            data.append(startIndex + i).append(".0");
            for (int d = 1; d < dimensions; d++) {
                data.append(',').append(fill);
            }
            data.append("]}");
        }
        return "{\"object\":\"list\",\"model\":\"text-embedding-test\",\"data\":["
                + data
                + "],\"usage\":{\"prompt_tokens\":"
                + count * 3
                + ",\"total_tokens\":"
                + count * 3
                + "}}";
    }

    /** 校验请求（一条 "ping"）的正常响应。 */
    public static String ping(int dimensions) {
        return vectors(1, dimensions, 0);
    }
}
