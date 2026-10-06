package com.lzq.commercecare.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank
        @Size(max = 1000)
        String question,

        @Min(1)
        @Max(10)
        int topK
) {
}
