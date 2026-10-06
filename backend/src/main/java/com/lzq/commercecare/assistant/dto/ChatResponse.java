package com.lzq.commercecare.assistant.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        List<Source> sources
) {
    public record Source(
            String sourceId,
            String title,
            Integer chunkIndex,
            Double score
    ){
    }
}
