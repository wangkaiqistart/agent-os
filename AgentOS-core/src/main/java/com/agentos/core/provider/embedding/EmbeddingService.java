package com.agentos.core.provider.embedding;

import java.util.List;

/** 文本向量化门面；向量存储与检索由后续 user story 负责。 */
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
