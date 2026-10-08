package com.lzq.commercecare.knowledge.dto;

import java.util.List;

/**
 * 混合检索响应；RRF分数用于排序，不代表证据充分性或答案置信度。
 */
public record HybridSearchResponse(String question, List<Result> results) {
    public record Result(
            String documentId,
            String sourceId,
            String title,
            String category,
            String productModel,
            String topic,
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