package cn.hollis.llm.mentor.know.engine.chat.service;

import cn.hollis.llm.mentor.know.engine.ai.constant.KnowEngineIntent;
import cn.hollis.llm.mentor.know.engine.ai.service.KnowEngineChatAiService;
import cn.hollis.llm.mentor.know.engine.ai.service.PromptService;
import cn.hollis.llm.mentor.know.engine.business.converter.CarInfoConverter;
import cn.hollis.llm.mentor.know.engine.business.converter.MyCarConverter;
import cn.hollis.llm.mentor.know.engine.business.entity.CarInfo;
import cn.hollis.llm.mentor.know.engine.business.entity.MyCar;
import cn.hollis.llm.mentor.know.engine.business.service.CarInfoService;
import cn.hollis.llm.mentor.know.engine.business.service.MyCarService;
import cn.hollis.llm.mentor.know.engine.chat.entity.ChatParam;
import cn.hollis.llm.mentor.know.engine.chat.memory.DatabaseChatMemoryStore;
import cn.hollis.llm.mentor.know.engine.document.entity.TableMeta;
import cn.hollis.llm.mentor.know.engine.document.service.KnowEngineTableMetaService;
import cn.hollis.llm.mentor.know.engine.document.service.KnowledgeSegmentService;
import cn.hollis.llm.mentor.know.engine.rag.modules.*;
import cn.hollis.llm.mentor.know.engine.rag.modules.reranker.BgeScoringModel;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static cn.hollis.llm.mentor.know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

@Service
@Slf4j
public class ChatApplicationService {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private RestClient restClient;

    @Autowired
    private Driver neo4jDriver;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowEngineTableMetaService knowEngineTableMetaService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MyCarService myCarService;

    @Autowired
    private CarInfoService carInfoService;

    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    @Value("classpath:prompts/text-to-sql-prompt.txt")
    private Resource textToSqlPrompt;

    @Value("classpath:sql/retrieve_tables.sql")
    private Resource tablesSql;

    /**
     * 流式对话
     * <p>
     * 1. 根据意图识别结果，判断是否需要车辆信息
     * 2. 如果车辆信息不完善，则返回车辆信息不完善提示
     * 3. 根据意图识别结果，判断是否需要车辆信息
     * </p>
     */
    public Flux<String> chat(ChatParam chatParam) {
        KnowEngineIntent intent = KnowEngineIntent.getIntent(chatParam.intentRecognitionResult());

        // 如果是维保服务、技术支持，则需要车辆信息
        if (intent == KnowEngineIntent.CAR_MAINTENANCE
                || intent == KnowEngineIntent.CAR_TECH_SUPPORT) {
            if (chatParam.intentRecognitionResult().entities().car_id() == null) {
                List<MyCar> myCars = myCarService.getCarByUserId(chatParam.userId());
                if (CollectionUtils.isEmpty(myCars)) {
                    return Flux.just("[WARN]:您还没有添加车辆信息，请先添加车辆信息");
                } else if (myCars.size() >= 1) {
                    return Flux.just("[CARD]:请先选择车辆")
                            .concatWith(Flux.just("[CARD_CHOICE_MYCAR]:" + JSON.toJSONString(MyCarConverter.INSTANCE.toVOList(myCars))));
                }
            }
        }

        // 如果是营销政策，则需要车辆信息
        if (intent == KnowEngineIntent.CAR_MARKETING) {
            if (chatParam.intentRecognitionResult().entities().car_model() == null) {
                List<CarInfo> carInfoList = carInfoService.getCarInfoByBrand(null);
                return Flux.just("[CARD]:请先选择您要咨询的车辆")
                        .concatWith(Flux.just("[CARD_CHOICE_CAR]:" + JSON.toJSONString(CarInfoConverter.INSTANCE.toVOList(carInfoList))));
            }
        }

        return doChat(chatParam);
    }

    /**
     * 流式对话
     * <p>
     * 使用 Flux.create() 将 RAG 管道各环节的进度消息与 LLM 流式输出桥接到同一个 Flux 中，
     * 确保进度消息在对应的 LLM token 之前到达前端。
     * <p>
     * 进度推送环节：
     * <ol>
     *   <li>问题改写 — 由 {@link KnowEngineQueryTransformer} 发送</li>
     *   <li>问题路由 — 由 {@link KnowEngineQueryRouter} 发送</li>
     *   <li>排序筛选 — 由 {@link ProgressAwareContentAggregator} 发送</li>
     *   <li>生成回答 — 由 {@link ProgressAwareContentAggregator} 在聚合完成后发送</li>
     * </ol>
     *
     * @param chatParam 对话参数
     */
    public Flux<String> doChat(ChatParam chatParam) {

        return Flux.<String>create(sink -> {
                    // 进度回调：同时写入 sink 和外部回调
                    Consumer<String> processCallback = sink::next;

                    // 构建查询改写器（带进度回调）
                    KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(chatModel, chatParam.messageId(), processCallback);

                    // 构建嵌入式检索器（带进度回调）
                    ProgressAwareContentRetriever embeddingRetriever = new ProgressAwareContentRetriever(KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationKnn.builder().build())
                            .maxResults(5)
                            .minScore(0.5)
                            .embeddingModel(openAiEmbeddingModel)
                            .restClient(restClient)
                            .indexName(INDEX_NAME)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .build(), processCallback);
                    // 构建全文检索器（带进度回调）
                    ProgressAwareContentRetriever fullTextRetriever = new ProgressAwareContentRetriever(ElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationFullText.builder().build())
                            .restClient(restClient)
                            .indexName(INDEX_NAME)
                            .maxResults(5)
                            .build(), processCallback);
                    // 构建 SQL 检索器（带进度回调）
                    ProgressAwareContentRetriever sqlRetriever = null;
                    try {
                        // 拼接静态表结构 + table_meta 中动态创建的表结构
                        String databaseStructure = buildDatabaseStructure();
                        sqlRetriever = new ProgressAwareContentRetriever(
                                KnowEngineSqlDatabaseContentRetriever.builder()
                                        .dataSource(dataSource)
                                        .promptTemplate(new PromptTemplate(textToSqlPrompt.getContentAsString(UTF_8)))
                                        .databaseStructure(databaseStructure)
                                        .chatModel(chatModel)
                                        .fallbackRetriever(embeddingRetriever)
                                        .build(), processCallback);
                    } catch (IOException e) {
                        log.warn("Error creating SQL retriever", e);
                    }

                    // 构建 Neo4j 检索器（带进度回调）
                    ProgressAwareContentRetriever neo4jRetriever = new ProgressAwareContentRetriever(Neo4jText2CypherRetriever.builder()
                            .graph(Neo4jGraph.builder()
                                    .driver(neo4jDriver)
                                    .build())
                            .chatModel(chatModel)
                            .build(), processCallback);

                    // 构建评分模型（带进度回调）
                    OnnxScoringModel scoringModel = BgeScoringModel.getInstance();

                    //  构建内容聚合器（带进度回调）
                    ContentAggregator contentAggregator = new ProgressAwareContentAggregator(
                            ReRankingContentAggregator.builder()
                                    .scoringModel(scoringModel)
                                    .maxResults(5)
                                    .querySelector(queryToContents -> queryToContents.keySet().iterator().next())
                                    .build(),
                            processCallback, chatParam.assistantMessageId(), chatMessageService
                    );

                    // 构建系统消息（带进度回调）
                    String prompt = promptService.getPrompt(chatParam.intentRecognitionResult());

                    // 构建内容注入器（带进度回调）
                    ContentInjector contentInjector = new DefaultContentInjector();

                    // 构建查询路由器（带进度回调）
                    RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                            .queryRouter(new KnowEngineQueryRouter(List.of(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever), chatModel, processCallback))
                            .queryTransformer(queryTransformer)
                            .contentAggregator(contentAggregator)
                            .contentInjector(contentInjector)
                            .build();
                    // 构建聊天服务（带进度回调）
                    KnowEngineChatAiService knowEngineChatAiService = AiServices.builder(KnowEngineChatAiService.class)
                            .chatModel(chatModel)
                            .streamingChatModel(streamingChatModel)
                            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                    .id(memoryId)
                                    .maxMessages(10)
                                    .chatMemoryStore(databaseChatMemoryStore)
                                    .build())
                            .systemMessage(prompt)
                            .retrievalAugmentor(retrievalAugmentor)
                            .build();

                    // 订阅 LLM 流式输出，桥接到 sink
                    AtomicBoolean firstToken = new AtomicBoolean(true);
                    StringBuilder contentBuilder = new StringBuilder();
                    Disposable disposable = knowEngineChatAiService.streamChat(chatParam.conversationId(), chatParam.content())
                            .doOnNext(token -> {
                                // 首个 token 到达时，如果之前没有发出"正在生成回答"，则补发
                                // （正常情况下由 ProgressAwareContentAggregator 已发出，此处为兜底）
                                if (firstToken.compareAndSet(true, false)) {
                                    // 标记已开始接收 token
                                }
                                contentBuilder.append(token);
                            })
                            .doOnComplete(() -> chatMessageService.updateContent(chatParam.assistantMessageId(), contentBuilder.toString()))
                            .subscribe(sink::next, sink::error, sink::complete);

                    // 取消时同步取消内部订阅
                    sink.onCancel(disposable::dispose);
                })
                .subscribeOn(Schedulers.boundedElastic())
                // publishOn 引入异步边界：boundedElastic 线程专用于执行阻塞 RAG 操作，
                // parallel 线程独立运行 drain loop，确保进度消息能及时推送到前端 SSE 响应
                .publishOn(Schedulers.parallel());
    }

    /**
     * 构建数据库结构描述
     * <p>
     * 将静态表结构（retrieve_tables.sql）与 table_meta 表中动态创建的表结构合并，
     * 作为 Text2SQL Prompt 的 databaseStructure 参数，使 LLM 感知所有可查询的表。
     */
    private String buildDatabaseStructure() throws IOException {
        StringBuilder sb = new StringBuilder();
        // 静态表结构
        sb.append(tablesSql.getContentAsString(UTF_8));

        // 从 table_meta 读取动态创建的表结构
        List<TableMeta> tableMetas = knowEngineTableMetaService.list();
        if (!CollectionUtils.isEmpty(tableMetas)) {
            sb.append("\n\n");
            String dynamicSql = tableMetas.stream()
                    .filter(meta -> meta.getCreateSql() != null && !meta.getCreateSql().isBlank())
                    .map(TableMeta::getCreateSql)
                    .collect(Collectors.joining("\n\n"));
            sb.append(dynamicSql);
        }
        return sb.toString();
    }
}
