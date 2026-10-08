package com.lzq.commercecare.routing.dto;

import java.util.List;

/**
 * 识别结果只描述型号，不判断政策是否可回答。
 * mentions保存原文提及，方便解释大小写归一与重复提及。
 */
public record ModelRecognitionResponse(
        Status status,
        String productModel,
        List<String> detectedModels,
        List<String> unknownMentions,
        List<String> mentions,
        String message
) {
    public ModelRecognitionResponse {
        detectedModels = List.copyOf(detectedModels);
        unknownMentions = List.copyOf(unknownMentions);
        mentions = List.copyOf(mentions);
    }

    public enum Status {
        RESOLVED, MISSING, UNKNOWN, MULTIPLE
    }
}
