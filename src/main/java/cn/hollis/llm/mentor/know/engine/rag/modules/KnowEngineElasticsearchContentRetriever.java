package cn.hollis.llm.mentor.know.engine.rag.modules;

import cn.hollis.llm.mentor.know.engine.document.service.KnowledgeSegmentService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.*;
import dev.langchain4j.store.embedding.filter.Filter;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static cn.hollis.llm.mentor.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_ID;
import static cn.hollis.llm.mentor.know.engine.rag.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * KnowEngine Elasticsearch 内容检索器
 * <p>
 * 基于 Elasticsearch 的向量检索实现，支持以下特性：
 * <ul>
 *   <li><b>向量检索 (KNN)</b>：使用 Embedding 模型将查询文本向量化，进行相似度搜索</li>
 *   <li><b>全文检索</b>：支持 Elasticsearch 全文搜索</li>
 *   <li><b>混合检索</b>：结合向量检索和全文检索（需 Elasticsearch 相应许可证）</li>
 *   <li><b>关联内容扩展</b>：自动检索兄弟分段 (brother chunk) 和父分段 (parent chunk) 内容</li>
 * </ul>
 * <p>
 * <b>关联内容扩展机制：</b>
 * <ul>
 *   <li>兄弟分段：具有相同父分段的其他子分段，用于获取完整上下文</li>
 *   <li>父分段：从 Redis 中读取父分段的完整文本，替换子分段以获得更完整的语义</li>
 * </ul>
 * <p>
 *
 * @see ElasticsearchContentRetriever
 * @see ContentRetriever
 */
public class KnowEngineElasticsearchContentRetriever extends AbstractElasticsearchEmbeddingStore implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchContentRetriever.class);
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;
    private final KnowledgeSegmentService knowledgeSegmentService;

    /**
     * Creates an instance of ElasticsearchContentRetriever using a RestClient.
     *
     * @param configuration  Elasticsearch retriever configuration to use (knn, script, full text, hybrid, hybrid with reranker)
     * @param restClient     Elasticsearch Rest Client (mandatory)
     * @param indexName      Elasticsearch index name (optional). Default value: "default".
     *                       Index will be created automatically if not exists.
     * @param embeddingModel Embedding model to be used by the retriever
     * @param maxResults     Maximum number of results to retrieve
     * @param minScore       Minimum score threshold for retrieved results
     * @param filter         Filter to apply during retrieval
     */
    public KnowEngineElasticsearchContentRetriever(
            ElasticsearchConfiguration configuration,
            RestClient restClient,
            String indexName,
            EmbeddingModel embeddingModel,
            final int maxResults,
            final double minScore,
            final Filter filter,
            KnowledgeSegmentService knowledgeSegmentService) {
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = filter;
        this.knowledgeSegmentService = knowledgeSegmentService;
        this.initialize(configuration, restClient, indexName);
    }

    /**
     * 根据查询条件检索相关内容
     * <p>
     * 根据当前配置的检索模式执行内容检索，支持以下三种模式：
     * <ul>
     *   <li><b>全文检索</b>：当配置为 {@link ElasticsearchConfigurationFullText} 时，直接执行全文搜索</li>
     *   <li><b>混合检索</b>：当配置为 {@link ElasticsearchConfigurationHybrid} 时，结合向量检索和全文检索</li>
     *   <li><b>向量检索（默认）</b>：将查询文本向量化后进行 KNN 相似度搜索</li>
     * </ul>
     * <p>
     * 在向量检索模式下，检索结果还会进行关联内容扩展：
     * <ul>
     *   <li>兄弟分段扩展：根据 brotherChunkId 检索同一父分段下的其他兄弟分段，补全上下文</li>
     *   <li>父分段替换：根据 parentChunkId 从 Redis 中读取父分段的完整文本，替换子分段以获得更完整的语义</li>
     * </ul>
     *
     * @param query 查询对象，包含待检索的文本内容
     * @return 检索到的内容列表，包含原始检索结果及扩展的关联内容
     */
    @Override
    public List<Content> retrieve(final Query query) {
        // 全文检索模式：直接执行全文搜索并返回结果
        if (configuration instanceof ElasticsearchConfigurationFullText) {
            log.debug("Using a full text search query");
            return this.fullTextSearch(query.text()).stream()
                    .map(t -> Content.from(
                            t,
                            Map.of(
                                    ContentMetadata.SCORE, t.metadata().getDouble(ContentMetadata.SCORE.name()),
                                    ContentMetadata.EMBEDDING_ID,
                                    t.metadata().getString(ContentMetadata.EMBEDDING_ID.name()))))
                    .toList();
        }
        // 将查询文本转换为向量
        Embedding referenceEmbedding = embeddingModel.embed(query.text()).content();
        // 构建向量搜索请求，设置查询向量、最大返回数量、最低相似度分数和过滤条件
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(referenceEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();

        // 混合检索模式：结合向量检索和全文检索
        if (configuration instanceof ElasticsearchConfigurationHybrid) {
            return mapResultsToContentList(this.hybridSearch(request, query.text()));
        }

        // 向量检索模式（默认）：执行 KNN 向量相似度搜索
        List<Content> searchContents = mapResultsToContentList(this.search(request));
        // 去重并按文本内容排序
        searchContents = searchContents.stream().distinct().sorted(Comparator.comparing(content -> content.textSegment().text())).toList();
        List<Content> finalContents = new ArrayList<>(searchContents);

        // 兄弟分段缓存和父分段缓存，避免重复查询
        Map<String, List<Content>> brotherDocMap = new HashMap<>();
        Map<String, List<Content>> parentDocMap = new HashMap<>();

        Iterator<Content> iterator = searchContents.iterator();

        for (; iterator.hasNext(); ) {
            Content content = iterator.next();
            // 兄弟分段扩展：检索具有相同 brotherChunkId 的其他兄弟分段
            String brotherChunkId = content.textSegment().metadata().getString(BROTHER_CHUNK_ID);
            if (brotherChunkId != null) {
                List<Content> cachedBrotherDocs = brotherDocMap.get(brotherChunkId);
                if (cachedBrotherDocs != null) {
                    // 命中缓存，直接使用已检索的兄弟分段
                    finalContents.addAll(cachedBrotherDocs);
                } else {
                    // 未命中缓存，按 brotherChunkId 过滤检索兄弟分段
                    Filter brotherFilter = metadataKey(BROTHER_CHUNK_ID).isEqualTo(brotherChunkId);
                    request = EmbeddingSearchRequest.builder()
                            .filter(brotherFilter)
                            .build();
                    List<Content> brotherDocs = mapResultsToContentList(this.search(request));
                    brotherDocMap.put(brotherChunkId, brotherDocs);
                    finalContents.addAll(brotherDocs);
                }
            }

            // 父分段替换：用父分段的完整文本替换子分段，获取更完整的语义
            String parentChunkId = content.textSegment().metadata().getString(PARENT_CHUNK_ID);
            if (parentChunkId != null) {
                List<Content> cachedParentDocs = parentDocMap.get(parentChunkId);
                if (cachedParentDocs != null) {
                    // 如果已经缓存中有过这个父分段了，说明已经用过了，这里就不用再加了，避免重复
                    finalContents.remove(content);
                } else if (knowledgeSegmentService != null) {
                    // 读取 parentChunk 的文本内容
                    String segmentText = knowledgeSegmentService.getTextByChunkId(parentChunkId);
                    if (segmentText != null) {
                        // 用父分段文本构造新的 Content，替换当前的子分段内容
                        TextSegment parentSegment = TextSegment.from(segmentText, content.textSegment().metadata());
                        Content parentContent = Content.from(parentSegment, content.metadata());
                        List<Content> parentDocs = List.of(parentContent);
                        parentDocMap.put(parentChunkId, parentDocs);
                        finalContents.remove(content);
                        finalContents.addAll(parentDocs);
                    } else {
                        log.warn("parentChunk not found in Redis, chunkId: {}", parentChunkId);
                        finalContents.remove(content);
                    }
                }
            }
        }
        return finalContents;
    }

    private List<Content> mapResultsToContentList(EmbeddingSearchResult<TextSegment> searchResult) {
        List<Content> result = searchResult.matches().stream()
                .filter(f -> f.score() > minScore)
                .map(m -> Content.from(
                        m.embedded(),
                        Map.of(
                                ContentMetadata.SCORE, m.score(),
                                ContentMetadata.EMBEDDING_ID, m.embeddingId())))
                .toList();
        log.debug("Found [{}] relevant documents in Elasticsearch index [{}].", result.size(), indexName);
        return result;
    }

    public static KnowEngineElasticsearchContentRetriever.Builder builder() {
        return new KnowEngineElasticsearchContentRetriever.Builder();
    }

    public static class Builder {

        private RestClient restClient;
        private String indexName = "default";
        private ElasticsearchConfiguration configuration =
                ElasticsearchConfigurationKnn.builder().build();
        private EmbeddingModel embeddingModel;
        private int maxResults;
        private double minScore;
        private Filter filter;
        private KnowledgeSegmentService knowledgeSegmentService;

        /**
         * @param restClient Elasticsearch RestClient.
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder restClient(RestClient restClient) {
            this.restClient = restClient;
            return this;
        }

        /**
         * @param indexName Elasticsearch index name (optional). Default value: "default".
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * @param configuration the configuration to use
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder configuration(ElasticsearchConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder filter(Filter filter) {
            this.filter = filter;
            return this;
        }


        public KnowEngineElasticsearchContentRetriever.Builder knowledgeSegmentService(KnowledgeSegmentService knowledgeSegmentService) {
            this.knowledgeSegmentService = knowledgeSegmentService;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever build() {
            return new KnowEngineElasticsearchContentRetriever(
                    configuration, restClient, indexName, embeddingModel, maxResults, minScore, filter, knowledgeSegmentService);
        }
    }
}