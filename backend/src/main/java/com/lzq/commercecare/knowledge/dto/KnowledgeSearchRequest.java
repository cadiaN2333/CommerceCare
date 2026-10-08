package com.lzq.commercecare.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 三路检索共享请求；不传型号时执行原有的无过滤检索。
 */
public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 1000) String question,
        @Min(1) @Max(10) int topK,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        String productModel
) {
}