package com.lzq.commercecare.routing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 型号识别请求；与检索请求分开，便于独立评测。 */
public record ModelRecognitionRequest(
        @NotBlank @Size(max = 1000) String question
) {
}
