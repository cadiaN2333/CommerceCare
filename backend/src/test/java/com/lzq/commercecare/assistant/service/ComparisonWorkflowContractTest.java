package com.lzq.commercecare.assistant.service;

import java.util.List;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 缺少任一侧候选时不能调用生成服务。 */
class ComparisonWorkflowContractTest {
    @Test
    void missingEvidenceStopsComparison() throws Exception {
        Class<?> type = assertDoesNotThrow(
                () -> Class.forName("com.lzq.commercecare.assistant.service.ModelComparisonService"));
        var search = mock(KnowledgeSearchService.class);
        var rag = mock(RagChatService.class);
        when(search.search("保修比较", 2, "SoundBee-A2")).thenReturn(List.of());
        Object service = type.getConstructor(KnowledgeSearchService.class, RagChatService.class)
                .newInstance(search, rag);
        Object result = type.getMethod("compare", String.class, int.class, List.class)
                .invoke(service, "保修比较", 2, List.of("SoundBee-A2", "SoundBee-A2-Plus"));
        ChatResponse response = (ChatResponse) result.getClass().getMethod("response").invoke(result);
        assertEquals(ChatResponse.Status.INSUFFICIENT_EVIDENCE, response.status());
        verifyNoInteractions(rag);
    }
}
