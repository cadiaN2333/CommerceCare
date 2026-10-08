package com.lzq.commercecare.knowledge.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 关键词召回：文本查询负责排序，精确型号过滤只限制候选资格。
 */
@Service
public class LuceneKeywordSearchService {

    private static final String ID_FIELD = "id";
    private static final String TITLE_FIELD = "title";
    private static final String CONTENT_FIELD = "content";
    private static final String SOURCE_ID_FIELD = "sourceId";
    private static final String CATEGORY_FIELD = "category";
    private static final String MODEL_FIELD = "productModel";
    private static final String TOPIC_FIELD = "topic";
    private static final String CHUNK_INDEX_FIELD = "chunkIndex";

    private static final Map<String, Float> FIELD_BOOSTS =
            Map.of(TITLE_FIELD, 2.0f);

    private final Analyzer analyzer;
    private final Directory directory;
    private final IndexWriter indexWriter;

    public LuceneKeywordSearchService(
            @Value("${commercecare.search.lucene-path:./data/lucene}")
            String indexPath
    ) throws IOException {
        Path path = Path.of(indexPath).toAbsolutePath().normalize();
        Files.createDirectories(path);

        this.analyzer = new CJKAnalyzer();
        this.directory = FSDirectory.open(path);

        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setSimilarity(new BM25Similarity());

        this.indexWriter = new IndexWriter(directory, config);
    }

    public synchronized void upsert(List<Document> documents) {
        try {
            for (Document document : documents) {
                indexWriter.updateDocument(
                        new Term(ID_FIELD, document.getId()),
                        toLuceneDocument(document)
                );
            }

            indexWriter.commit();
        } catch (IOException exception) {
            throw new UncheckedIOException("写入 Lucene 索引失败。", exception);
        }
    }

    public List<Document> search(String question, int topK) {
        return search(question, topK, null);
    }

    public List<Document> search(String question, int topK, String productModel) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        try (DirectoryReader reader = DirectoryReader.open(indexWriter)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());

            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{TITLE_FIELD, CONTENT_FIELD},
                    analyzer,
                    FIELD_BOOSTS
            );

            Query query = parser.parse(QueryParser.escape(question));
            if (productModel != null) {
                // FILTER不增加文本相关分数；TermQuery区分A2与A2-Plus。
                query = new BooleanQuery.Builder()
                        .add(query, BooleanClause.Occur.MUST)
                        .add(new TermQuery(new Term(MODEL_FIELD, productModel)),
                                BooleanClause.Occur.FILTER)
                        .build();
            }
            TopDocs topDocs = searcher.search(query, topK);

            List<Document> results = new ArrayList<>();
            var storedFields = searcher.storedFields();

            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                ScoreDoc hit = topDocs.scoreDocs[i];
                org.apache.lucene.document.Document stored =
                        storedFields.document(hit.doc);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put(SOURCE_ID_FIELD, stored.get(SOURCE_ID_FIELD));
                metadata.put("title", stored.get(TITLE_FIELD));
                metadata.put(CATEGORY_FIELD, stored.get(CATEGORY_FIELD));
                if (stored.get(MODEL_FIELD) != null) {
                    metadata.put(MODEL_FIELD, stored.get(MODEL_FIELD));
                }
                if (stored.get(TOPIC_FIELD) != null) {
                    metadata.put(TOPIC_FIELD, stored.get(TOPIC_FIELD));
                }
                metadata.put(
                        CHUNK_INDEX_FIELD,
                        Integer.parseInt(stored.get(CHUNK_INDEX_FIELD))
                );
                metadata.put("keywordRank", i + 1);

                results.add(
                        Document.builder()
                                .id(stored.get(ID_FIELD))
                                .text(stored.get(CONTENT_FIELD))
                                .metadata(metadata)
                                .score((double) hit.score)
                                .build()
                );
            }

            return results;
        } catch (IOException | ParseException exception) {
            throw new IllegalStateException("Lucene 检索失败。", exception);
        }
    }

    private org.apache.lucene.document.Document toLuceneDocument(
            Document source
    ) {
        Map<String, Object> metadata = source.getMetadata();
        org.apache.lucene.document.Document document =
                new org.apache.lucene.document.Document();

        document.add(new StringField(
                ID_FIELD,
                source.getId(),
                Field.Store.YES
        ));
        document.add(new TextField(
                TITLE_FIELD,
                metadataText(metadata, "title"),
                Field.Store.YES
        ));
        document.add(new TextField(
                CONTENT_FIELD,
                source.getText(),
                Field.Store.YES
        ));
        document.add(new StringField(
                SOURCE_ID_FIELD,
                metadataText(metadata, SOURCE_ID_FIELD),
                Field.Store.YES
        ));
        document.add(new StringField(
                CATEGORY_FIELD,
                metadataText(metadata, CATEGORY_FIELD),
                Field.Store.YES
        ));
        document.add(new StoredField(
                CHUNK_INDEX_FIELD,
                metadataText(metadata, CHUNK_INDEX_FIELD)
        ));

        // StringField不做分词，型号仅作精确过滤并保存用于响应核验。
        if (metadata.get(MODEL_FIELD) != null) {
            document.add(new StringField(MODEL_FIELD,
                    metadataText(metadata, MODEL_FIELD), Field.Store.YES));
        }
        if (metadata.get(TOPIC_FIELD) != null) {
            document.add(new StringField(TOPIC_FIELD,
                    metadataText(metadata, TOPIC_FIELD), Field.Store.YES));
        }
        return document;
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    @PreDestroy
    public void close() throws IOException {
        try {
            indexWriter.close();
        } finally {
            try {
                directory.close();
            } finally {
                analyzer.close();
            }
        }
    }
}