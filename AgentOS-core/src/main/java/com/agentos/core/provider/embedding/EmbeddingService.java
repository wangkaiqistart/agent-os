package com.agentos.core.provider.embedding;

import java.util.List;

/**
 * 文本向量化门面；向量存储与检索由后续 user story 负责。
 *
 * <p>Embedding 的作用是把文字转换成一串数字，之后才能进行语义相似度搜索。
 * core 只定义门面，不关心具体使用哪家模型服务。
 */
public interface EmbeddingService {

    /** 按输入顺序返回文本向量。 */
    List<float[]> embed(List<String> texts);

    /** 返回单段文本向量。 */
    float[] embed(String text);

    /** 返回当前服务状态。 */
    EmbeddingStatus status();

    /** 返回配置的向量维度。 */
    int dimensions();
}
