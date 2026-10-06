package com.lzq.commercecare.assistant.controller;

import com.lzq.commercecare.assistant.dto.ChatRequest;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.assistant.service.RagChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return ragChatService.chat(
                request.question(),
                request.topK()
        );
    }
}
