package com.lzq.commercecare.assistant.service;

import java.util.List;
import java.util.Map;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 检索和生成用模拟依赖，精确验证预算、证据污染与双侧引用。 */
class ModelComparisonServiceTest {
    private static final String A = "SoundBee-A2", B = "SoundBee-A2-Plus", Q = "保修比较";
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final RagChatService rag = mock(RagChatService.class);
    private final ModelComparisonService service = new ModelComparisonService(search, rag);
    private Document doc(String id, String model) {
        return new Document(id, "演示资料", Map.of("productModel", model, "sourceId", id));
    }
    private ChatResponse.Source source(int ref, String id) {
        return new ChatResponse.Source(ref, id, "演示", 0, 0.9);
    }
    private RagChatService.EvidenceAnswer complete() {
        var left = source(1, "a"); var right = source(2, "b");
        return new RagChatService.EvidenceAnswer(new ChatResponse(ChatResponse.Status.ANSWERED,
                "演示比较", List.of(left, right)), Map.of(A, List.of(left), B, List.of(right)));
    }
    private void normal() {
        when(search.search(Q, 2, A)).thenReturn(List.of(doc("a", A)));
        when(search.search(Q, 2, B)).thenReturn(List.of(doc("b", B)));
    }
    @Test void budgetAndOneGeneration() {
        when(search.search(Q, 3, A)).thenReturn(List.of(doc("a", A)));
        when(search.search(Q, 3, B)).thenReturn(List.of(doc("b", B)));
        when(rag.answerFromDocuments(eq(Q), anyList())).thenReturn(complete());
        assertEquals(ChatResponse.Status.ANSWERED, service.compare(Q, 10, List.of(A, B)).response().status());
        verify(search).search(Q, 3, A); verify(search).search(Q, 3, B);
        verify(rag, times(1)).answerFromDocuments(eq(Q), anyList());
    }
    @Test void oneSideEmptyDoesNotGenerate() {
        when(search.search(Q, 2, A)).thenReturn(List.of(doc("a", A)));
        when(search.search(Q, 2, B)).thenReturn(List.of());
        assertEquals(ChatResponse.Status.INSUFFICIENT_EVIDENCE, service.compare(Q, 2, List.of(A, B)).response().status());
        verifyNoInteractions(rag);
    }
    @Test void wrongModelRejected() {
        when(search.search(Q, 2, A)).thenReturn(List.of(doc("a", B)));
        assertThrows(ResponseStatusException.class, () -> service.compare(Q, 2, List.of(A, B)));
        verifyNoInteractions(rag);
    }
    @Test void missingModelRejected() {
        when(search.search(Q, 2, A)).thenReturn(List.of(new Document("a", "资料", Map.of())));
        assertThrows(ResponseStatusException.class, () -> service.compare(Q, 2, List.of(A, B)));
        verifyNoInteractions(rag);
    }
    @Test void excessiveCandidatesRejected() {
        when(search.search(Q, 2, A)).thenReturn(List.of(doc("1", A), doc("2", A), doc("3", A)));
        assertThrows(ResponseStatusException.class, () -> service.compare(Q, 2, List.of(A, B)));
        verifyNoInteractions(rag);
    }
    @Test void duplicateIdConflictingModelsRejected() {
        when(search.search(Q, 2, A)).thenReturn(List.of(doc("same", A)));
        when(search.search(Q, 2, B)).thenReturn(List.of(doc("same", B)));
        assertThrows(ResponseStatusException.class, () -> service.compare(Q, 2, List.of(A, B)));
        verifyNoInteractions(rag);
    }
    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void identicalDuplicatesDeduplicatedInStableOrder() {
        Document a = doc("a", A);
        when(search.search(Q, 2, A)).thenReturn(List.of(a, a));
        when(search.search(Q, 2, B)).thenReturn(List.of(doc("b", B)));
        when(rag.answerFromDocuments(eq(Q), anyList())).thenReturn(complete());
        service.compare(Q, 2, List.of(A, B));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(rag).answerFromDocuments(eq(Q), captor.capture());
        assertEquals(List.of("a", "b"), captor.getValue().stream().map(Document::getId).toList());
    }
    @Test void oneSidedCitationsRejected() {
        normal(); var left = source(1, "a");
        when(rag.answerFromDocuments(eq(Q), anyList())).thenReturn(new RagChatService.EvidenceAnswer(
                new ChatResponse(ChatResponse.Status.ANSWERED, "只有一侧", List.of(left)), Map.of(A, List.of(left))));
        assertThrows(ResponseStatusException.class, () -> service.compare(Q, 2, List.of(A, B)));
    }
    @Test void unknownDimensionCanBeInsufficient() {
        normal();
        when(rag.answerFromDocuments(eq(Q), anyList())).thenReturn(new RagChatService.EvidenceAnswer(
                new ChatResponse(ChatResponse.Status.INSUFFICIENT_EVIDENCE, "无法确认", List.of()), Map.of()));
        assertTrue(service.compare(Q, 2, List.of(A, B)).sourceGroups().isEmpty());
    }
    @Test void dependencyFailurePropagates() {
        var error = new IllegalStateException("模拟检索失败");
        when(search.search(Q, 2, A)).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, () -> service.compare(Q, 2, List.of(A, B))));
    }
    @Test void inputMustBeTwoDifferentModels() {
        assertThrows(IllegalArgumentException.class, () -> service.compare(Q, 2, List.of(A, A)));
        assertThrows(IllegalArgumentException.class, () -> service.compare(Q, 2, List.of(A)));
        assertThrows(IllegalArgumentException.class, () -> service.compare(Q, 0, List.of(A, B)));
        verifyNoInteractions(search, rag);
    }
}
