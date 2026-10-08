package com.lzq.commercecare.assistant.service;

import java.util.List;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.assistant.dto.RoutedChatResponse;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import com.lzq.commercecare.routing.service.ModelRecognitionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 只处理需要具体型号的咨询；通用问题的意图分流尚未实现。
 * 澄清分支提前结束，避免无效检索和模型生成。
 */
@Service
public class RoutedChatService {
    private final ModelRecognitionService recognitionService;
    private final RagChatService ragChatService;

    public RoutedChatService(
            ModelRecognitionService recognitionService,
            RagChatService ragChatService
    ) {
        this.recognitionService = recognitionService;
        this.ragChatService = ragChatService;
    }

    public RoutedChatResponse chat(String question, int topK) {
        ModelRecognitionResponse recognition = recognitionService.recognize(question);

        if (recognition.status() != ModelRecognitionResponse.Status.RESOLVED) {
            return new RoutedChatResponse(
                    RoutedChatResponse.Status.NEEDS_CLARIFICATION,
                    RoutedChatResponse.Strategy.CLARIFY_MODEL,
                    clarificationMessage(recognition.status()),
                    recognition,
                    List.of()
            );
        }

        String model = recognition.productModel();
        if (model == null || model.isBlank()) {
            // 识别结果异常时不能传null进入无过滤基线。
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "型号识别结果缺少规范型号。");
        }

        // 保留原问题与条件，只把规范型号作为结构化过滤参数。
        ChatResponse response = ragChatService.chat(question, topK, model);
        RoutedChatResponse.Status status = switch (response.status()) {
            case ANSWERED -> RoutedChatResponse.Status.ANSWERED;
            case INSUFFICIENT_EVIDENCE ->
                    RoutedChatResponse.Status.INSUFFICIENT_EVIDENCE;
        };
        return new RoutedChatResponse(
                status,
                RoutedChatResponse.Strategy.MODEL_FILTERED_VECTOR,
                response.answer(),
                recognition,
                response.sources()
        );
    }

    private String clarificationMessage(ModelRecognitionResponse.Status status) {
        return switch (status) {
            case MISSING ->
                    "请提供完整型号，并将型号与原问题一起发送，例如：SoundBee-A2坏了能退吗？";
            case UNKNOWN ->
                    "当前目录未包含部分型号名称，请核对完整型号后，将型号与原问题一起重新发送。";
            case MULTIPLE ->
                    "识别到多个型号。当前入口仅处理单型号咨询，尚未支持比较；可将每个完整型号与其问题分别发送。";
            case RESOLVED ->
                    throw new IllegalArgumentException("已明确的型号不应进入澄清分支。");
        };
    }
}
