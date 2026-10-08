package com.lzq.commercecare.knowledge.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * 按片段的稳定 ID 合并两路候选，并根据各路排名计算等权 RRF 分数。
 * 原始向量分数和 BM25 分数仅保留用于诊断，不参与融合加法。
 */
@Service
public class ReciprocalRankFusionService {

    // 平滑排名贡献的常量，与召回数量和最终 topK 是不同参数。
    private static final int RRF_CONSTANT = 60;

    /**
     * 输入列表需要已经按各自检索算法排序；缺席一路的候选仍能参与融合。
     */
    public List<FusedDocument> fuse(
            List<Document> vectorResults,
            List<Document> keywordResults,
            int topK
    ) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须大于等于 1。");
        }

        // 同一来源可以包含多个片段，因此不能使用 sourceId 作为合并键。
        Map<String, Accumulator> candidates = new HashMap<>();

        addRanking(vectorResults, candidates, true);
        addRanking(keywordResults, candidates, false);

        // 分数相同时按 ID 排序，保证相同输入具有稳定输出。
        return candidates.values().stream()
                .map(Accumulator::toResult)
                .sorted(
                        Comparator.comparingDouble(FusedDocument::fusionScore)
                                .reversed()
                                .thenComparing(
                                        result -> result.document().getId()
                                )
                )
                .limit(topK)
                .toList();
    }

    // 记录各路候选的排名和原始分数；排名从 1 开始。
    private void addRanking(
            List<Document> documents,
            Map<String, Accumulator> candidates,
            boolean vectorChannel
    ) {
        Set<String> seenIds = new HashSet<>();

        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String id = document.getId();

            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("检索片段缺少稳定 ID。");
            }

            // 同一路中重复出现的片段只贡献一次。
            if (!seenIds.add(id)) {
                continue;
            }

            int rank = i + 1;

            Accumulator candidate = candidates.computeIfAbsent(
                    id,
                    ignored -> new Accumulator(document)
            );

            if (vectorChannel) {
                candidate.vectorRank = rank;
                candidate.vectorScore = document.getScore();
            } else {
                candidate.keywordRank = rank;
                candidate.keywordScore = document.getScore();
            }
        }
    }

    // 汇集同一片段在两路中的表现，避免修改检索服务返回的原始文档。
    private static final class Accumulator {

        private final Document document;
        private Integer vectorRank;
        private Integer keywordRank;
        private Double vectorScore;
        private Double keywordScore;

        private Accumulator(Document document) {
            this.document = document;
        }

        private FusedDocument toResult() {
            double fusionScore = 0.0;

            // 只有该路实际召回此片段时才贡献分数，缺席一路贡献为零。
            if (vectorRank != null) {
                fusionScore += 1.0 / (RRF_CONSTANT + vectorRank);
            }

            if (keywordRank != null) {
                fusionScore += 1.0 / (RRF_CONSTANT + keywordRank);
            }

            return new FusedDocument(
                    document,
                    vectorRank,
                    keywordRank,
                    vectorScore,
                    keywordScore,
                    fusionScore
            );
        }
    }

    /**
     * 融合结果；空排名表示未被该路召回，fusionScore 不是相似度或置信概率。
     */
    public record FusedDocument(
            Document document,
            Integer vectorRank,
            Integer keywordRank,
            Double vectorScore,
            Double keywordScore,
            double fusionScore
    ) {
    }
}
