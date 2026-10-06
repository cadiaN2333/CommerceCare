package com.lzq.commercecare.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeIndexRequest(
        @NotBlank String sourceId,
        @NotBlank String title,
        @NotBlank
        @Size(max = 100_000)
        String content
) {
}