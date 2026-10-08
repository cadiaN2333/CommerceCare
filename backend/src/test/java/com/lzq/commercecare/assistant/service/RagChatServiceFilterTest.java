package com.lzq.commercecare.assistant.service;

import java.util.List;
import java.util.Map;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证型号约束真正进入召回层，异常候选在生成前被阻止。 */
class RagChatServiceFilterTest {
    @Test
    void baselineKeepsTwoArgumentSearch() {
        var fixture = fixture();
        when(fixture.search().search("原问题", 2)).thenReturn(List.of());
        var response = fixture.service().chat("原问题", 2);
        assertEquals(ChatResponse.Status.INSUFFICIENT_EVIDENCE, response.status());
        verify(fixture.search()).search("原问题", 2);
        verifyNoMoreInteractions(fixture.search());
        verifyNoInteractions(fixture.client());
    }

    @Test
    void filteredEmptySearchDoesNotGenerate() {
        var fixture = fixture();
        when(fixture.search().search("原问题", 2, "SoundBee-A2")).thenReturn(List.of());
        var response = fixture.service().chat("原问题", 2, "SoundBee-A2");
        assertEquals(ChatResponse.Status.INSUFFICIENT_EVIDENCE, response.status());
        verify(fixture.search()).search("原问题", 2, "SoundBee-A2");
        verifyNoMoreInteractions(fixture.search());
        verifyNoInteractions(fixture.client());
    }

    @Test
    void wrongModelCandidateStopsBeforeGeneration() {
        var fixture = fixture();
        when(fixture.search().search("原问题", 2, "SoundBee-A2")).thenReturn(
                List.of(document("wrong", "SoundBee-A2-Plus")));
        var failure = assertThrows(ResponseStatusException.class,
                () -> fixture.service().chat("原问题", 2, "SoundBee-A2"));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verifyNoInteractions(fixture.client());
    }

    @Test
    void missingModelCandidateStopsBeforeGeneration() {
        var fixture = fixture();
        when(fixture.search().search("原问题", 2, "SoundBee-A2")).thenReturn(
                List.of(new Document("legacy", "演示资料", Map.of())));
        assertThrows(ResponseStatusException.class,
                () -> fixture.service().chat("原问题", 2, "SoundBee-A2"));
        verifyNoInteractions(fixture.client());
    }

    @Test
    void mixedCandidatesCannotPartiallyEnterContext() {
        var fixture = fixture();
        when(fixture.search().search("原问题", 2, "SoundBee-A2")).thenReturn(
                List.of(document("right", "SoundBee-A2"),
                        document("wrong", "SoundBee-A3")));
        assertThrows(ResponseStatusException.class,
                () -> fixture.service().chat("原问题", 2, "SoundBee-A2"));
        verifyNoInteractions(fixture.client());
    }

    @Test
    void retrievalFailureIsNotInsufficientEvidence() {
        var fixture = fixture();
        var failure = new IllegalStateException("模拟检索失败");
        when(fixture.search().search("原问题", 2, "SoundBee-A2")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.service().chat("原问题", 2, "SoundBee-A2")));
        verifyNoInteractions(fixture.client());
    }

    @Test
    void blankModelCannotBecomeBaseline() {
        var fixture = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().chat("原问题", 2, " "));
        verifyNoInteractions(fixture.search(), fixture.client());
    }

    @Test
    void matchingCandidateGeneratesAnswerAndMapsSource() {
        // 真实ChatClient完成模板渲染和结构化转换，仅底层模型响应为模拟。
        var search = mock(KnowledgeSearchService.class);
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(modelResponse(
                "{\"answerable\":true,\"answer\":\"演示回答\",\"sourceNumbers\":[1]}"));
        String question = "SoundBee-A2签收16天怎么处理？";
        when(search.search(question, 2, "SoundBee-A2")).thenReturn(List.of(
                new Document("doc-id", "演示质量问题按维修流程处理", Map.of(
                        "productModel", "SoundBee-A2", "sourceId", "demo-source",
                        "title", "演示政策", "chunkIndex", 0))));
        var service = new RagChatService(ChatClient.builder(model), search);
        var response = service.chat(question, 2, "SoundBee-A2");

        assertEquals(ChatResponse.Status.ANSWERED, response.status());
        assertEquals("演示回答", response.answer());
        assertEquals(1, response.sources().size());
        assertEquals("demo-source", response.sources().getFirst().sourceId());
        assertEquals(1, response.sources().getFirst().referenceNumber());
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        assertTrue(captor.getValue().getUserMessage().getText().contains(question));
        assertTrue(captor.getValue().getUserMessage().getText().contains("演示质量问题"));
    }

    @Test
    void matchingButInsufficientCandidateReturnsNoSources() {
        var search = mock(KnowledgeSearchService.class);
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(modelResponse(
                "{\"answerable\":false,\"answer\":\"\",\"sourceNumbers\":[]}"));
        when(search.search("原问题", 2, "SoundBee-A2")).thenReturn(
                List.of(document("right", "SoundBee-A2")));
        var service = new RagChatService(ChatClient.builder(model), search);
        var response = service.chat("原问题", 2, "SoundBee-A2");
        assertEquals(ChatResponse.Status.INSUFFICIENT_EVIDENCE, response.status());
        assertTrue(response.sources().isEmpty());
        verify(model).call(any(Prompt.class));
    }

    private org.springframework.ai.chat.model.ChatResponse modelResponse(String json) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(json))));
    }

    private Document document(String id, String model) {
        return new Document(id, "演示资料", Map.of("productModel", model));
    }

    private Fixture fixture() {
        var search = mock(KnowledgeSearchService.class);
        var client = mock(ChatClient.class);
        var builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        return new Fixture(new RagChatService(builder, search), search, client);
    }

    private record Fixture(
            RagChatService service, KnowledgeSearchService search, ChatClient client
    ) {
    }
}
