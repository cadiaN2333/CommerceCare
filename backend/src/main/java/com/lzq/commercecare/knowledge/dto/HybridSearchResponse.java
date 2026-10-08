package com.lzq.commercecare.knowledge.dto;

import java.util.List;

/**
 * 混合检索响应：score 是 RRF 分数，scoreType 明确标识算法。
 * 两路排名和原始分数单独返回，便于分析各路对融合结果的贡献。
 */
public record HybridSearchResponse(
        String question,
        List<Result> results
) {
    // documentId 标识片段；同一 sourceId 下的不同 chunk 可以分别入选。
    public record Result(
            String documentId,
            String sourceId,
            String title,
            String category,
            Integer chunkIndex,
            String content,
            double score,
            String scoreType,
            Integer vectorRank,
            Integer keywordRank,
            Double vectorScore,
            Double keywordScore
    ) {
    }
}
