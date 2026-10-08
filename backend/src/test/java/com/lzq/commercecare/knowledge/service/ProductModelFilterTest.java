package com.lzq.commercecare.knowledge.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证约束进入召回层，而不是返回后才删除不适用片段。
 * 本地测试不启动数据库，也不调用Embedding模型。
 */
class ProductModelFilterTest {
    @TempDir
    Path indexPath;

    @Test
    void vectorSearchCarriesExactModelExpression() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeSearchService service = new KnowledgeSearchService(store);

        service.search("质量问题", 2, "SoundBee-A2");
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());

        assertEquals(2, captor.getValue().getTopK());
        assertEquals(
                new FilterExpressionBuilder().eq("productModel", "SoundBee-A2").build(),
                captor.getValue().getFilterExpression()
        );
    }

    @Test
    void vectorBaselineHasNoModelExpression() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        new KnowledgeSearchService(store).search("质量问题", 2);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        assertNull(captor.getValue().getFilterExpression());
    }

    @Test
    void keywordSearchExcludesSimilarAndMissingModels() throws Exception {
        // 使用临时索引，不碰应用的data/lucene目录。
        LuceneKeywordSearchService service =
                new LuceneKeywordSearchService(indexPath.toString());
        try {
            List<Document> inputs = List.of(
                    document("a2", "SoundBee-A2"),
                    document("a2plus", "SoundBee-A2-Plus"),
                    new Document("legacy", "演示质量问题处理",
                            Map.of("sourceId", "legacy", "title", "演示质量问题",
                                    "category", "after_sales", "chunkIndex", 0))
            );
            service.upsert(inputs);
            service.upsert(inputs);

            List<Document> hits = service.search("质量问题", 10, "SoundBee-A2");
            assertEquals(1, hits.size());
            assertEquals("a2", hits.getFirst().getId());
            assertEquals("SoundBee-A2", hits.getFirst().getMetadata().get("productModel"));
            assertEquals(3, service.search("质量问题", 10).size());
            assertTrue(service.search("质量问题", 10, "Unknown-X").isEmpty());
            assertTrue(service.search("质量问题", 10, "soundbee-a2").isEmpty());
        } finally {
            service.close();
        }
    }

    private Document document(String id, String model) {
        return new Document(id, "演示质量问题处理",
                Map.of("sourceId", id, "title", "演示质量问题",
                        "category", "after_sales", "chunkIndex", 0,
                        "productModel", model, "topic", "quality"));
    }
}
