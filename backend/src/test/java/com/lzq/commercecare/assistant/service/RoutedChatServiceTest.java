package com.lzq.commercecare.assistant.service;

import java.util.List;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.assistant.dto.RoutedChatResponse;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import com.lzq.commercecare.routing.service.ModelRecognitionService;
import com.lzq.commercecare.routing.service.ProductModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实规则识别与模拟RAG，验证路由，不调用外部模型。 */
class RoutedChatServiceTest {
    private final RagChatService rag = mock(RagChatService.class);
    private final RoutedChatService service = new RoutedChatService(
            new ModelRecognitionService(new ProductModelCatalog()), rag, new ComparisonTaskService(), mock(ModelComparisonService.class));

    @Test
    void missingModelClarifiesWithoutRag() {
        var response = service.chat("耳机坏了能退吗？", 2);
        assertEquals(RoutedChatResponse.Status.NEEDS_CLARIFICATION, response.status());
        assertEquals(RoutedChatResponse.Strategy.CLARIFY_MODEL, response.strategy());
        assertEquals(ModelRecognitionResponse.Status.MISSING,
                response.modelRecognition().status());
        assertTrue(response.sources().isEmpty());
        verifyNoInteractions(rag);
    }

    @Test
    void unknownModelDoesNotRetrieveAnotherModel() {
        var response = service.chat("SoundBee-Z9能退吗？", 2);
        assertEquals(RoutedChatResponse.Status.NEEDS_CLARIFICATION, response.status());
        assertEquals(List.of("SoundBee-Z9"),
                response.modelRecognition().unknownMentions());
        assertTrue(response.sources().isEmpty());
        verifyNoInteractions(rag);
    }

    @Test
    void mixedKnownAndUnknownModelsKeepBoth() {
        var response = service.chat("SoundBee-A2和SoundBee-Z9比较一下", 2);
        assertEquals(RoutedChatResponse.Status.NEEDS_CLARIFICATION, response.status());
        assertEquals(List.of("SoundBee-A2"), response.modelRecognition().detectedModels());
        assertEquals(List.of("SoundBee-Z9"), response.modelRecognition().unknownMentions());
        assertNull(response.modelRecognition().productModel());
        verifyNoInteractions(rag);
    }

    @Test
    void multipleModelsDoNotSelectFirst() {
        var response = service.chat("SoundBee-A2和SoundBee-A2-Plus有什么区别？", 2);
        assertEquals(RoutedChatResponse.Status.NEEDS_CLARIFICATION, response.status());
        assertEquals(List.of("SoundBee-A2", "SoundBee-A2-Plus"),
                response.modelRecognition().detectedModels());
        assertNull(response.modelRecognition().productModel());
        verifyNoInteractions(rag);
    }

    @Test
    void resolvedModelKeepsOriginalQuestionAndPassesCanonicalName() {
        String question = "soundbee-a2签收7天未拆封，配件齐全能退吗？";
        var sources = List.of(new ChatResponse.Source(
                1, "expanded-v1-soundbee-a2-return", "演示退货规则", 0, 0.9));
        when(rag.chatWithEvidence(question, 2, "SoundBee-A2")).thenReturn(
                new RagChatService.EvidenceAnswer(new ChatResponse(ChatResponse.Status.ANSWERED, "演示回答", sources), java.util.Map.of("SoundBee-A2", sources)));

        var response = service.chat(question, 2);
        assertEquals(RoutedChatResponse.Status.ANSWERED, response.status());
        assertEquals(RoutedChatResponse.Strategy.MODEL_FILTERED_VECTOR, response.strategy());
        assertEquals("SoundBee-A2", response.modelRecognition().productModel());
        assertEquals(sources, response.sources());
        verify(rag).chatWithEvidence(question, 2, "SoundBee-A2");
        verifyNoMoreInteractions(rag);
    }

    @Test
    void insufficientEvidenceIsDifferentFromClarification() {
        String question = "SoundBee-A2支持哪些蓝牙编码格式？";
        when(rag.chatWithEvidence(question, 2, "SoundBee-A2")).thenReturn(
                new RagChatService.EvidenceAnswer(new ChatResponse(ChatResponse.Status.INSUFFICIENT_EVIDENCE,
                        "资料无法确认", List.of()), java.util.Map.of()));

        var response = service.chat(question, 2);
        assertEquals(RoutedChatResponse.Status.INSUFFICIENT_EVIDENCE, response.status());
        assertEquals(RoutedChatResponse.Strategy.MODEL_FILTERED_VECTOR, response.strategy());
        assertEquals(ModelRecognitionResponse.Status.RESOLVED,
                response.modelRecognition().status());
        assertTrue(response.sources().isEmpty());
    }

    @Test
    void serviceFailureIsNotNormalBusinessFallback() {
        String question = "SoundBee-A2能退吗？";
        var failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模拟模型失败");
        when(rag.chatWithEvidence(question, 2, "SoundBee-A2")).thenThrow(failure);
        assertSame(failure, assertThrows(ResponseStatusException.class,
                () -> service.chat(question, 2)));
    }

    @Test
    void invalidResolvedResultCannotReachUnfilteredRag() {
        var brokenRecognition = mock(ModelRecognitionService.class);
        when(brokenRecognition.recognize("SoundBee-A2能退吗？")).thenReturn(
                new ModelRecognitionResponse(ModelRecognitionResponse.Status.RESOLVED,
                        null, List.of("SoundBee-A2"), List.of(), List.of("SoundBee-A2"),
                        "模拟异常识别结果"));
        var routed = new RoutedChatService(brokenRecognition, rag, new ComparisonTaskService(), mock(ModelComparisonService.class));
        var failure = assertThrows(ResponseStatusException.class,
                () -> routed.chat("SoundBee-A2能退吗？", 2));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verifyNoInteractions(rag);
    }

    @Test
    void explicitTwoModelComparisonKeepsMultipleRecognition() {
        String question = "SoundBee-A2和SoundBee-A2-Plus保修有什么区别？";
        var comparison = mock(ModelComparisonService.class);
        var left = new ChatResponse.Source(1, "a", "左侧", 0, 0.9);
        var right = new ChatResponse.Source(2, "b", "右侧", 0, 0.9);
        when(comparison.compare(question, 2, List.of("SoundBee-A2", "SoundBee-A2-Plus")))
                .thenReturn(new RagChatService.EvidenceAnswer(
                        new ChatResponse(ChatResponse.Status.ANSWERED, "演示比较", List.of(left, right)),
                        java.util.Map.of("SoundBee-A2", List.of(left), "SoundBee-A2-Plus", List.of(right))));
        var routed = new RoutedChatService(new ModelRecognitionService(new ProductModelCatalog()),
                rag, new ComparisonTaskService(), comparison);
        var response = routed.chat(question, 2);
        assertEquals(RoutedChatResponse.Strategy.MODEL_COMPARISON, response.strategy());
        assertEquals(ModelRecognitionResponse.Status.MULTIPLE, response.modelRecognition().status());
        assertNull(response.modelRecognition().productModel());
        assertEquals(2, response.sourceGroups().size());
        verify(comparison).compare(question, 2, List.of("SoundBee-A2", "SoundBee-A2-Plus"));
        verifyNoInteractions(rag);
    }

    @Test
    void threeModelsNeverInvokeComparison() {
        var comparison = mock(ModelComparisonService.class);
        var routed = new RoutedChatService(new ModelRecognitionService(new ProductModelCatalog()),
                rag, new ComparisonTaskService(), comparison);
        assertEquals(RoutedChatResponse.Status.NEEDS_CLARIFICATION,
                routed.chat("SoundBee-A2、SoundBee-A3、SoundBee-B1保修比较", 2).status());
        verifyNoInteractions(rag, comparison);
    }
}
