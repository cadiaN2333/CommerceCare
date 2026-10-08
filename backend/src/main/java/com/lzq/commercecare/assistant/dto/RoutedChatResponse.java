package com.lzq.commercecare.assistant.dto;

import java.util.List;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;

/**
 * 型号专属聊天入口的响应；识别状态和回答状态分别表达。
 * 澄清表示缺少适用型号，证据不足表示无法支持当前问题。
 */
public record RoutedChatResponse(
        Status status,
        Strategy strategy,
        String answer,
        ModelRecognitionResponse modelRecognition,
        List<ChatResponse.Source> sources
) {
    public RoutedChatResponse {
        sources = List.copyOf(sources);
    }

    public enum Status {
        ANSWERED, INSUFFICIENT_EVIDENCE, NEEDS_CLARIFICATION
    }

    public enum Strategy {
        MODEL_FILTERED_VECTOR, CLARIFY_MODEL
    }
}
