package com.lzq.commercecare.knowledge.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        String question,
        List<Result> results
) {
    public record Result(
            String sourceId,
            String title,
            String category,
            Integer chunkIndex,
            String content,
            Double score
    ) {
    }
}