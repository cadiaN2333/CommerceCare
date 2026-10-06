package com.lzq.commercecare.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(
        @NotBlank
        @Size(max = 1000)
        String question,

        @Min(1)
        @Max(10)
        int topK
){
}
