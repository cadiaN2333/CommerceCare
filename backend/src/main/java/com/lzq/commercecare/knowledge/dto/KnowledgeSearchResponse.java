package com.lzq.commercecare.knowledge.dto;

import java.util.List;

/**
 * 返回实际命中片段的型号与主题，便于核查过滤是否真正生效。
 */
public record KnowledgeSearchResponse(String question, List<Result> results) {
    public record Result(
            String sourceId,
            String title,
            String category,
            String productModel,
            String topic,
            Integer chunkIndex,
            String content,
            Double score
    ) {
    }
}