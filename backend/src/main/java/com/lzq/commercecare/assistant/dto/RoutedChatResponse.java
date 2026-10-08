package com.lzq.commercecare.assistant.dto;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
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
        List<ChatResponse.Source> sources,
        Map<String, List<ChatResponse.Source>> sourceGroups
) {
    public RoutedChatResponse {
        sources = List.copyOf(sources);
        Map<String, List<ChatResponse.Source>> copy = new LinkedHashMap<>();
        sourceGroups.forEach((model, values) -> copy.put(model, List.copyOf(values)));
        sourceGroups = Collections.unmodifiableMap(copy);
    }

    public enum Status {
        ANSWERED, INSUFFICIENT_EVIDENCE, NEEDS_CLARIFICATION
    }

    public enum Strategy {
        MODEL_FILTERED_VECTOR, MODEL_COMPARISON, CLARIFY_MODEL
    }
}
