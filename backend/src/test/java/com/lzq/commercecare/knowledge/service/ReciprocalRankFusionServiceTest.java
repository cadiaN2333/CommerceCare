package com.lzq.commercecare.knowledge.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证融合契约及边界，不依赖数据库、Lucene 索引或外部模型接口。
 */
class ReciprocalRankFusionServiceTest {

    private final ReciprocalRankFusionService service =
            new ReciprocalRankFusionService();

    @Test
    void emptyListsProduceNoCandidates() {
        assertTrue(service.fuse(List.of(), List.of(), 2).isEmpty());
    }

    @Test
    void sameChunkAcrossChannelsIsMerged() {
        Document vector = document("a", "policy", 0.8);
        Document keyword = document("a", "policy", 12.0);

        var results = service.fuse(List.of(vector), List.of(keyword), 2);
        assertEquals(1, results.size());
        var result = results.getFirst();
        assertEquals(1, result.vectorRank().intValue());
        assertEquals(1, result.keywordRank().intValue());
        assertEquals(2.0 / 61.0, result.fusionScore(), 1e-12);
        assertEquals(0.8, result.vectorScore(), 1e-12);
        assertEquals(12.0, result.keywordScore(), 1e-12);
    }

    @Test
    void vectorOnlyCandidateHasNoKeywordContribution() {
        var result = service.fuse(
                List.of(document("a", "policy", 0.8)), List.of(), 2
        ).getFirst();

        assertEquals(1.0 / 61.0, result.fusionScore(), 1e-12);
        assertNull(result.keywordRank());
        assertNull(result.keywordScore());
    }

    @Test
    void keywordOnlyCandidateHasNoVectorContribution() {
        var result = service.fuse(
                List.of(), List.of(document("a", "policy", 10.0)), 2
        ).getFirst();

        assertEquals(1.0 / 61.0, result.fusionScore(), 1e-12);
        assertNull(result.vectorRank());
        assertNull(result.vectorScore());
    }

    @Test
    void repeatedIdWithinOneChannelContributesOnlyOnce() {
        Document a = document("a", "policy", 0.8);
        Document b = document("b", "policy", 0.6);
        var results = service.fuse(List.of(a, a, b), List.of(), 5);

        assertEquals(2, results.size());
        assertEquals(1.0 / 61.0, results.get(0).fusionScore(), 1e-12);
        assertEquals(3, results.get(1).vectorRank().intValue());
        assertEquals(1.0 / 63.0, results.get(1).fusionScore(), 1e-12);
    }

    @Test
    void differentChunksOfSameSourceRemainSeparate() {
        var results = service.fuse(
                List.of(
                        document("chunk-0", "same-source", 0.9),
                        document("chunk-1", "same-source", 0.8)
                ), List.of(), 2
        );

        assertEquals(2, results.size());
        assertEquals(List.of("chunk-0", "chunk-1"),
                results.stream().map(item -> item.document().getId()).toList());
    }

    @Test
    void rawScoreScaleDoesNotOverrideRanksAndTiesUseIds() {
        var results = service.fuse(
                List.of(document("b", "b", 0.001), document("a", "a", 999.0)),
                List.of(document("a", "a", 0.001), document("b", "b", 999.0)),
                2
        );

        double expected = 1.0 / 61.0 + 1.0 / 62.0;
        assertEquals(List.of("a", "b"),
                results.stream().map(item -> item.document().getId()).toList());
        assertEquals(expected, results.get(0).fusionScore(), 1e-12);
        assertEquals(expected, results.get(1).fusionScore(), 1e-12);
    }

    @Test
    void finalTopKLimitsFusedResults() {
        List<Document> candidates = List.of(
                document("a", "a", 0.9),
                document("b", "b", 0.8),
                document("c", "c", 0.7)
        );

        var results = service.fuse(candidates, candidates, 1);
        assertEquals(1, results.size());
        assertEquals("a", results.getFirst().document().getId());
    }

    @Test
    void invalidTopKIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.fuse(List.of(), List.of(), 0));
    }

    private Document document(String id, String sourceId, double score) {
        return Document.builder()
                .id(id)
                .text("虚构测试片段")
                .metadata(Map.of("sourceId", sourceId))
                .score(score)
                .build();
    }
}
