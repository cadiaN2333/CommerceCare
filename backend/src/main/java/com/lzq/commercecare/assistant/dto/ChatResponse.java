package com.lzq.commercecare.assistant.dto;

import java.util.List;

public record ChatResponse(
        Status status,
        String answer,
        List<Source> sources
) {
    public enum Status {
        ANSWERED,
        INSUFFICIENT_EVIDENCE
    }

    public record Source(
            int referenceNumber,
            String sourceId,
            String title,
            Integer chunkIndex,
            Double score
    ) {
    }
}