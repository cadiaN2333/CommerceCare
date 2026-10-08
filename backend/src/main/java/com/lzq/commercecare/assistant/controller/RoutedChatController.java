package com.lzq.commercecare.assistant.controller;

import com.lzq.commercecare.assistant.dto.ChatRequest;
import com.lzq.commercecare.assistant.dto.RoutedChatResponse;
import com.lzq.commercecare.assistant.service.RoutedChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 型号专属咨询入口；原chat接口保留为无过滤基线。
 * 客户端只提交问题与topK，规范型号由服务端识别。
 */
@RestController
@RequestMapping("/api/v1/chat/routed")
public class RoutedChatController {
    private final RoutedChatService service;

    public RoutedChatController(RoutedChatService service) {
        this.service = service;
    }

    @PostMapping
    public RoutedChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return service.chat(request.question(), request.topK());
    }
}
