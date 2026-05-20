# KnowEngine — 企业级智能知识引擎

> 基于 RAG 架构的企业知识管理与智能问答引擎，实现从文档接入、智能切片、向量化存储到多源检索、意图路由、流式对话的全链路闭环。

---

## 架构总览

### 对话流程（Query Path）

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            用户交互层 (SSE)                                        │
│       ChatController · upload.html · DingTalkBot(钉钉机器人)                     │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                │
                   ┌────────────▼────────────┐
                   │     异步标题生成          │
                   │  VirtualThread + flash  │
                   └─────────────────────────┘
                                │
              ┌─────────────────▼──────────────────┐
              │          1. 意图识别                 │
              │   IntentRecognitionService (LLM)    │
              │   Structured Output → Record        │
              │   · related? → 通用对话              │
              │   · intent? → Prompt 路由           │
              │   · entities → 结构化实体            │
              └────┬─────────────────────┬─────────┘
                   │ 不相关               │ 相关
           ┌───────▼───────┐    ┌────────▼─────────────────────────────────────┐
           │  通用对话      │    │           2. 查询改写                          │
           │  CommonChat   │    │  KnowEngineQueryTransformer (LLM)            │
           │  Service      │    │  4维策略: 简洁/抽象/纠错/标准化                  │
           └───────┬───────┘    │  改写结果 VirtualThread 异步回写 DB             │
                   │            └────────┬──────────────────────────────────────┘
                   │                     │
                   │         ┌───────────▼──────────────────────────────────────┐
                   │         │           3. 查询路由                             │
                   │         │  KnowEngineQueryRouter (LLM)                     │
                   │         │  {intent, strategy, confidence} → 路由决策        │
                   │         └──┬────────────┬────────────┬─────────────────────┘
                   │            │            │            │
                   │    ┌───────▼──┐   ┌─────▼────┐  ┌──▼──────────┐
                   │    │ knowledge│   │relational│  │  graph_db   │
                   │    │   _base  │   │   _db    │  │             │
                   │    └───────┬──┘   └─────┬────┘  └──┬──────────┘
                   │            │            │           │
    ┌──────────────┼────────────▼────────────▼───────────▼────────────────────────┐
    │              │            4. 多源内容检索 (ProgressAware)                    │
    │              │                                                              │
    │    ┌─────────▼─────────┐  ┌────────▼────────┐  ┌──────────▼──────────────┐  │
    │    │  ES Content       │  │  Text2SQL        │  │  Text2Cypher           │  │
    │    │  Retriever        │  │  Retriever       │  │  Retriever             │  │
    │    │                   │  │                  │  │                        │  │
    │    │  · KNN 向量检索     │  │  · LLM→SQL      │  │  · LLM→Cypher          │  │
    │    │  · 全文检索         │  │  · JDBC 执行     │  │  · Neo4j 执行          │  │
    │    │  · 混合检索         │  │  · table_meta   │  │  · Schema 自动感知      │  │
    │    │  · 父分段回溯       │  │  · Schema 驱动   │  │  · 降级回退 ES          │  │
    │    │  · 兄弟分段补全     │  │              │  │                         │  │
    │    └─────────┬─────────┘  └────────┬────────┘  └──────────┬──────────────┘  │
    │              │                     │                      │                  │
    │              └─────────────┬───────┘──────────────────────┘                  │
    │                            │                                                │
    │              ┌─────────────▼──────────────────────────────────────────────┐  │
    │              │           5. Reranking + 聚合                               │  │
    │              │  ProgressAwareContentAggregator                            │  │
    │              │  · BGE-RERANKER (ONNX 本地推理)                             │  │
    │              │  · 引用溯源 → RagReference → [REFERENCE] 事件               │  │
    │              └─────────────┬──────────────────────────────────────────────┘  │
    │                            │                                                │
    │              ┌─────────────▼──────────────────────────────────────────────┐  │
    │              │         6. LLM 流式生成 + 卡片输出                           │  │
    │              │  · Prompt 动态注入 (意图→领域 Prompt)                        │  │
    │              │  · Reactor Flux SSE 逐 Token 推送                           │  │
    │              │  · [CARD] 结构化卡片事件 (车型/订单)                           │  │
    │              └────────────────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────────────────────────────┘
```

### 知识入库流程（Ingestion Path）

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          知识管理管道 (Ingestion Pipeline)                         │
└──────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────┐    ┌──────────────┐    ┌──────────────────────┐    ┌──────────────┐    ┌──────────────┐
  │ 文件上传   │    │ 格式转换      │    │ 智能切片               │    │ 向量化嵌入     │    │ ES 索引存储   │
  │          │    │              │    │                      │    │              │    │              │
  │ · PDF    │    │ · MinerU     │    │ DocumentSplitter     │    │ text-         │    │ · KNN 索引   │
  │ · Word   │───▶│   深度解析    │───▶│   Factory            │───▶│ embedding-   │───▶│ · 全文索引   │
  │ · Excel  │    │ · Word→MD   │    │                      │    │   v4 (1536d) │    │ · 混合索引   │
  │ · MD/TXT │    │ · Excel直通  │    │ · TITLE  → Parent   │    │              │    │              │
  │ · CSV    │    │ · Tika 类型  │    │ · SMART  → Auto     │    │ 跳过:         │    │              │
  │          │    │   检测       │    │ · LENGTH → ByWord   │    │ skipEmbedding │    │              │
  └──────────┘    └──────┬───────┘    │ · REGEX  → ByRegex  │    │ =1 的父分段   │    │              │
                         │            │ · SEPARATOR         │    └──────────────┘    └──────────────┘
                         │            │ Excel → ExcelSplitter│
                         │            │   · 键值对模式        │
                         │            │   · HTML 表格模式     │
                         │            │ 雪花ID → chunkId     │
                         │            └──────────┬───────────┘
                         │                       │
                         │         ┌─────────────▼──────────────┐
                         │         │    Spring Event 事件驱动     │
                         │         │                            │
                         │         │  ConvertedEvent ──────────▶ │
                         │         │      ↓ 自动触发切片         │
                         │         │  ChunkedEvent ───────────▶ │
                         │         │      ↓ 自动触发向量化       │
                         │         │                            │
                         │         │  @DistributeLock 并发保护   │
                         │         │  XXL-Job 补偿 → 最终一致    │
                         │         └────────────────────────────┘
```

### 数据与基础设施层

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           数据存储层                                               │
├──────────────┬────────────────┬──────────────┬──────────────┬─────────────────────┤
│              │                │              │              │                     │
│   MySQL      │  Elasticsearch │    Neo4j     │    MinIO     │      Redis          │
│              │                │              │              │                     │
│ · 业务数据    │  · KNN 向量     │  · 车型图谱    │  · 原始文档  │  · 三级缓存 L2        │
│ · Text2SQL   │  · 全文索引     │  · 零部件      │  · 转换文档  │  · 分布式锁           │
│ · table_meta │  · 混合检索     │  · 故障链      │  · ZIP 包   │  · Redisson         │
│ · 会话/消息   │  · 父子分段     │  · APOC        │  · 多版本   │  · 父分段缓存         │
│ · 版本管理    │  · 权限过滤     │  · Text2Cypher│             │  · 权限缓存           │
│ · 权限元数据   │  · 版本过滤     │               │             │                     │
│ · 乐观锁      │  · 元数据过滤   │               │              │                    │
│ · 逻辑删除    │               │                │              │                    │
└──────────────┴───────────────┴────────────────┴──────────────┴────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────┐
│                           基础设施层                                               │
├──────────────┬───────────────┬──────────────┬──────────────┬─────────────────────┤
│              │               │              │              │                     │
│ SnowflakeId  │ @DistributeLock│  JsonUtil   │ VirtualThread│     XXL-Job        │
│              │               │              │              │                     │
│ · chunkId    │  · Redisson   │  · 7步修复    │  · 标题生成   │  · 补偿任务         │
│ · parent     │  · SpEL Key   │  · Markdown  │  · 改写回写   │  · 定时兜底         │
│ · brother    │  · 自动续期    │  · 引号/逗号   │  · 钉钉回调   │  · 失败重试         │
│ · 版本链路    │  · waitTime=0 │  · 容错包装   │  · Java 21   │  · 最终一致         │
│ · 全链路唯一   │  · 权限更新锁  │              │  · 零阻塞    │                     │
└──────────────┴───────────────┴──────────────┴──────────────┴─────────────────────┘
```

---

## 核心功能

### 1. 全链路知识接入 (Ingestion Pipeline)

从原始文档到可检索知识，全程自动化：

- **多格式解析**：支持 PDF（MinerU 深度解析）、Word、Excel、Markdown、TXT、Excel等
- **智能切片**（5 种策略，工厂模式按需选择）：
  - `MarkdownHeaderParentTextSplitter` — 基于标题层级切片，保留父子分段关系（Parent-Child Chunking），支持 chunkSize / overlap 精细控制、代码块保护、超出 chunkSize 自动二次切割
  - `MarkdownHeaderBrotherTextSplitter` — 兄弟分段关联，检索时自动补全同级上下文
  - `ExcelSplitter` — 针对结构化数据的双模式切片（键值对模式 & HTML 表格模式），按 chunkSize 智能分块，确保同一行数据不被拆分
  - `DocumentByWordSplitter` / `DocumentByRegexSplitter` — 按长度 / 正则 / 分隔符切分，满足不同文档结构需求
  - `SMART` 智能切分 — 自动使用 MarkdownHeaderParentTextSplitter，overlap 为 chunkSize 的 10%
- **事件驱动处理**：Spring `ApplicationEvent` 串联 文档转换 → 切片 → 向量化，每个阶段独立解耦
- **补偿机制**：XXL-Job 定时扫表，自动重试失败任务，保障最终一致性

### 2. 关键检索增强

- 查询改写（`KnowEngineQueryTransformer`）：4 维改写策略 — 简洁改写、抽象概念改写、错别字纠正、车型信息标准化。改写结果还通过 Java 21 的虚拟线程异步回写到数据库，不阻塞主流程。
- 父子分段扩展（`KnowEngineElasticsearchContentRetriever`）：命中子分段时，自动回溯父分段获取完整语义上下文。
- 本地 Reranking（`BgeScoringModel`）：基于 ONNX Runtime 加载 BGE-RERANKER 模型，进程内推理，零网络延迟
- 查询路由（`KnowEngineQueryRouter`）：三路由检索架构，由 LLM 智能识别问题，判断通过不同的数据源做检索
- **Text2SQL**（`SqlDatabaseContentRetriever`）：LLM 根据数据库 Schema 自动生成 SQL 并通过 JDBC 执行查询，支持结构化业务数据（订单、保险、召回信息等）的自然语言查询。基于 `table_meta` 表存储的表结构元数据驱动 Schema 感知，自动注入当前日期等上下文变量；查询结果以自然语言格式返回
- **Text2Cypher**（`Neo4jText2CypherRetriever`）：LLM 根据图数据库 Schema 自动生成 Cypher 查询语句，在 Neo4j 中执行实体关系检索，适用于车型图谱、零部件供应链、故障影响链等关系型场景。Cypher 查询失败时自动降级回退到 ES 检索，保障系统鲁棒性；查询结果与 ES 向量检索结果通过 `ContentAggregator` 统一聚合，经 Reranking 后共同参与最终生成

| 数据源 | 检索方式 | 适用场景 |
|--------|---------|--------|
| **Elasticsearch** | KNN 向量检索 + 全文检索 + 混合检索 | 语义相似性匹配、非结构化知识 |
| **MySQL** | Text2SQL（LLM 生成 SQL） | 结构化数据查询，如订单、保险、召回信息 |
| **Neo4j** | Text2Cypher（LLM 生成 Cypher + 降级回退） | 实体关系查询，如车型图谱、零部件供应链、故障影响链 |

### 3. 意图识别与动态 Prompt

- **6 大意图分类**：售前咨询与购买、售后维修与保养、车辆使用与技术指导、投诉与维权、汽车营销政策、其他
- **相关度判断**：LLM 首先判断用户问题是否与汽车领域相关，不相关问题直接走通用对话，避免 RAG 误检索
- **结构化实体提取**：车型、订单号、经销商、故障描述、预约时间、零部件、车辆功能
- **Prompt 动态路由**：根据识别结果加载对应的领域 Prompt 文件，Prompt 使用 `ConcurrentHashMap` 本地缓存，避免重复 IO
- **LangChain4j Structured Output**：通过 `@JsonPropertyDescription` 注解约束 LLM 输出为 `IntentRecognitionResult` Record，确保意图识别结果结构化、可编程处理

### 4. 流式对话体验

- **SSE 实时推送**：基于 Reactor `Flux` 的全链路流式输出，Token 级逐字推送
- **进度可见**：RAG 管道每个环节（意图识别 → 问题改写 → 问题路由 → 排序筛选 → 生成回答）均向前端推送 `[PROGRESS]` 事件，消除等待焦虑
- **RAG 引用溯源**：检索结果携带文档来源、分片内容、Rerank 分数，支持可解释性审查
- **异步标题生成**：新对话自动以 Java 21 虚拟线程 + qwen3.5-flash 轻量模型异步生成摘要标题，不阻塞首 Token 延迟；失败时保留临时标题，保障主流程不受影响
- **交互卡片输出**：SSE 流式响应中支持 `[CARD]` 结构化卡片事件，将车型对比、订单详情、召回信息等结构化数据以卡片形式呈现。LLM 通过 Prompt 引导在特定场景输出结构化 JSON 标记，后端解析后转换为 `[CARD]` 事件，前端根据卡片类型（`vehicle_info` / `order_detail` / `recall_notice` / `comparison_table`）渲染对应 UI 组件，支持点击展开详情、跳转链接等交互操作
- **钉钉机器人接入**：接入钉钉机器人，支持单聊和群聊 @机器人两种模式。钉钉消息回调经签名验证后转换为 KnowEngine 统一对话接口调用，流式收集完整回复后通过钉钉 API 回复；群聊模式自动识别 @内容 作为用户问题

### 5. 知识库管理

- **双类型知识库**：`DOCUMENT_SEARCH`（语义检索型）和 `DATA_QUERY`（数据查询型）
- **完整文档生命周期**：INIT → UPLOADED → CONVERTING → CONVERTED → CHUNKED → VECTOR_STORED
- **文档多版本管理**：支持同一知识文档的多版本共存，`knowledge_document.extension` JSON 字段存储版本元数据（`version`、`parentVersionDocId`、`changeLog`）。新版本发布后旧版本自动归档，检索时默认使用最新版本；版本切换通过修改 ES 中 `docVersion` 的 Filter 条件实现热切换，无需重新向量化；`expire_date` 字段支持文档到期后自动从检索结果中过滤；版本回滚一键切回历史版本，ES Filter 实时生效
- **文档检索权限体系**：基于角色和组织的文档访问控制，`knowledge_document.accessible_by` 字段存储文档可见范围。切片时将 `accessibleBy` 写入 `knowledge_segment.metadata`，向量化时同步到 ES 索引，检索时在 `EmbeddingSearchRequest` 中注入 `Filter` 条件按当前用户身份过滤。
- **MinIO 对象存储**：文档文件统一存储，支持 URL 直接访问
- **乐观锁 + 逻辑删除**：数据安全，防并发冲突

---

## 技术亮点

### 🔥 本地 Reranking — 零外部依赖的精排方案

基于 ONNX Runtime 在 JVM 进程内直接运行 BGE-RERANKER 模型，无需部署独立的 Reranking 微服务。采用双重检查锁单例模式，模型仅加载一次，全生命周期复用。解决了 macOS Monterey 兼容性问题（降级 onnxruntime 至 1.17.1），并支持 JAR 包内模型文件的 classpath 自动解析与临时文件释放。

### 🔥 父子分段检索 — 语义完整性的关键保障

检索时命中细粒度子分段后，自动通过 `parentChunkId` 回溯到父分段的完整文本进行替换，同时通过 `brotherChunkId` 补全同级的兄弟分段。这一机制解决了"切片后语义截断"的 RAG 经典痛点，在保持检索精度的同时，为 LLM 提供完整的上下文窗口。

### 🔥 三源智能路由 — 一句话自动选数据源

`KnowEngineQueryRouter` 利用 LLM 对用户 Query 进行语义分析，输出 `{intent, strategy, confidence}` 结构化决策，自动路由到 Elasticsearch / MySQL / Neo4j 中最合适的数据源。路由失败自动降级为空结果，保障系统鲁棒性。

**Text2SQL 路径**：当 `strategy = relational_db` 时，路由到 `SqlDatabaseContentRetriever`，LLM 根据 `table_meta` 表存储的表结构元数据生成 SQL，自动注入当前日期等上下文变量。

**Text2Cypher 路径**：当 `strategy = graph_db` 时，路由到 `Neo4jText2CypherRetriever`，LLM 根据图数据库 Schema 自动生成 Cypher 查询，无需预先编写查询模板。Cypher 查询失败时自动降级回退到 ES 检索

### 🔥 流式 RAG 管道 — 进度感知的端到端流式架构

在 `DefaultRetrievalAugmentor` 的标准流程中，通过装饰器模式（`ProgressAwareContentRetriever` / `ProgressAwareContentAggregator`）注入进度回调，将阻塞式 RAG 操作调度到 `Schedulers.boundedElastic()`，通过 `Flux.create()` + `publishOn(Schedulers.parallel())` 实现进度消息与 LLM Token 的有序混合推送。

SSE 事件类型扩展为 4 类：`[PROGRESS]`（处理进度通知）、`[REFERENCE]`（RAG 引用溯源）、`[CARD]`（结构化交互卡片）、Token（逐字文本）。其中 `[CARD]` 事件由 LLM 在特定场景输出结构化 JSON 标记触发，后端解析后转换为 `[CARD]:{"type":"vehicle_info", "data":{...}}` 格式推送，前端根据卡片类型渲染对应 UI 组件（表格、时间线、对比视图等），支持点击展开详情、跳转链接等交互操作。

钉钉机器人接入同一流式管道：钉钉消息回调经签名验证后，调用 `ChatApplicationService.streamChat()` 流式收集完整回复，通过钉钉 API 回复消息；长回复自动分片发送，`[CARD]` 事件自动转为钉钉 ActionCard 消息格式，`[REFERENCE]` 引用来源附带在回复中提升可信度。

### 🔥 Spring 事件驱动 — 文档处理的优雅编排

文档从上传到入库全程通过 Spring `ApplicationEvent` 串联，Controller 只负责发布事件，业务逻辑完全由 EventListener 异步驱动。结合 XXL-Job 补偿任务，实现"事件驱动 + 定时兜底"的最终一致性保障。

### 🔥 分布式锁注解 — 声明式并发控制

基于 Redisson + AOP 实现的 `@DistributeLock` 注解，支持 SpEL 表达式动态 Key、超时自动续期、等待超时等策略，一行注解即可保护关键业务操作（如文档上传、切片）的幂等性。

### 🔥 多格式文档的智能切片能力

针对 Markdown，MarkdownHeaderParentTextSplitter 基于标题层级进行 Parent-Child Chunking，支持代码块保护、按 chunkSize 二次切割、以及父子关系元数据注入。针对 Excel/CSV，ExcelSplitter 实现了 RAGFlow 风格的双模式输出（键值对模式和 HTML 表格模式），并且支持按字符数智能分块，确保同一行数据不会被拆到不同分片中。文件类型检测则通过文件头魔数实现，避免依赖文件扩展名。

### 🔥 RAG 引用溯源

ProgressAwareContentAggregator 在重排序完成后，会从检索结果中提取文档来源、分片内容、Rerank 分数等元数据，组装成 RagReference 结构，既异步持久化到数据库，也实时通过 [REFERENCE] 事件推送给前端，让每一次回答都有据可查。

### 🔥 雪花算法 ChunkId — 分布式唯一标识

所有知识分段的 `chunkId` 均通过自研 `SnowflakeIdGenerator` 生成，基于雪花算法（64 位：1 位符号位 + 41 位时间戳 + 10 位工作机器 ID + 12 位序列号），确保在分布式环境下全局唯一、趋势递增。相比数据库自增 ID，雪花 ID 天然支持分库分表、跨服务关联；相比 UUID，雪花 ID 更短、可排序、对索引更友好。`parentChunkId` 和 `brotherChunkId` 同样使用雪花 ID，贯穿切片 → 存储 → 检索 → 扩展全链路。

在文档多版本管理场景中，`parentVersionDocId` 通过雪花 ID 关联历史版本链，`knowledge_segment.metadata` 中的 `docVersion` 字段与雪花 ID 配合，实现版本热切换时的精准过滤——修改 ES 中 `docVersion` 的 Filter 条件即可切换活跃版本，无需重新向量化，雪花 ID 的趋势递增特性确保版本时序可追溯。`expire_date` 字段与版本元数据联动，文档到期后自动从检索结果中过滤。

### 🔥 分布式锁 — 文档处理并发控制

基于 Redisson + AOP 实现的 `@DistributeLock` 注解，支持 SpEL 表达式动态 Key（如 `#document.docId`）、场景隔离（`scene`）、超时自动续期、等待超时等策略。在文档切片（`split`）和向量化（`embedAndStore`）等关键操作上，通过 `@DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)` 实现同一文档的互斥处理：当 `waitTime = 0` 时，已持有锁的请求直接失败，避免重复切片或并发写入导致数据不一致。

### 🔥 Excel 双模式智能切片 — 表格数据的 RAG 适配

针对 Excel/CSV 的结构化数据特点，`ExcelSplitter` 提供两种输出模式：
- **键值对模式**（默认）：将每行数据转换为 `表头1：值1; 表头2：值2; ...` 格式，语义自包含，适合直接进行向量检索
- **HTML 表格模式**：保留原始表格结构，按 `chunkSize` 智能分块输出 `<table>` 片段，每个分块自动携带表头，确保独立可读

HTML 模式下采用行级完整性保障：同一行数据绝不会被拆分到不同分片中，即使该行超过 `chunkSize`，也保证至少包含一行。分块间通过共享表头实现上下文自足，解决了大表格跨分片后的信息丢失问题。

文件格式检测通过文件头魔数实现（ZIP 头 → `.xlsx`，OLE 头 → `.xls`，逗号+换行 → `.csv`），不依赖文件扩展名，防止用户重命名导致的解析错误。同时支持 BOM 编码自动检测（UTF-8/UTF-16BE/UTF-16LE）。

### 🔥 表格与图片跨分片完整性保障

Markdown 文档中的表格和图片在被 `chunkSize` 二次切割时面临语义截断风险，本系统通过以下机制解决：
- **父子分段保留完整原文**：超出 `chunkSize` 的分片会保留一份完整原文（标记 `skipEmbedding = 1`，不参与向量检索），同时生成多个子分片（携带 `parentChunkId`）。检索时命中子分片后，自动通过 `parentChunkId` 回溯到 Redis 缓存中的父分片完整文本进行替换，确保表格和图片的上下文完整性
- **兄弟分段自动补全**：通过 `brotherChunkId` + `brotherChunkIndex` + `brotherChunkTotal` 元数据，检索命中任一子分片时自动补全同级所有兄弟分段，拼出完整内容
- **代码块保护**：Markdown 切片器识别 ``` 代码围栏，将整个代码块作为一个不可分割的单元处理，避免代码被截断

### 🔥 虚拟线程 + Flash 模型 — 异步标题生成零延迟

新会话首条消息发出后，系统通过 `Thread.ofVirtual().name("title-summary-" + conversationId).start(...)` 启动 Java 21 虚拟线程，异步调用 qwen3.5-flash 轻量模型生成对话标题并回写数据库。关键设计：
- **零阻塞**：虚拟线程不占用平台线程，不阻塞 SSE 流式响应的首 Token 输出
- **Flash 模型选型**：使用 qwen3.5-flash 而非 qwen-max，标题生成任务对推理能力要求低，Flash 模型响应更快、成本更低
- **关闭思考模式**：通过 `customParameters(Map.of("enable_thinking", false))` 关闭 qwen3.5-flash 的 CoT 思考过程，进一步降低延迟
- **优雅降级**：标题生成失败时保留临时标题，不影响对话功能

### 🔥 意图识别 — LLM Structured Output 约束

通过 LangChain4j 的 `@AiService` + `@SystemMessage` + `@JsonPropertyDescription` 注解体系，将意图识别结果约束为 `IntentRecognitionResult` Record 类型，包含 `reasoning`（推理过程）、`related`（是否相关）、`intent`（意图分类）、`entities`（结构化实体）四个字段。LLM 输出被强制对齐到 Java 类型系统，避免了自由文本输出的不确定性。识别流程在 RAG 管道前端通过 `Mono.fromCallable().subscribeOn(Schedulers.boundedElastic())` 调度，不阻塞 WebFlux 事件循环。

### 🔥 多级缓存 Chunk 检索 — HashMap + Redis + MySQL 三级缓存

`KnowledgeSegmentServiceImpl.getTextByChunkId()` 实现了 HashMap → Redis → MySQL 的三级缓存策略，用于父子分段检索时快速获取父分段完整文本：
- **L1 缓存（HashMap）**：以 `chunkId` 为 Key，在当前线程缓存父分段完整文本，避免同一次检索中重复查询 Redis 和数据库
- **L2 缓存（Redis）**：以 `chunkId` 为 Key，30 秒 TTL，命中即返回
- **L3 回源（MySQL）**：Redis 未命中时查询 `knowledge_segment` 表，结果写入 Redis
- **防缓存击穿**：查询结果为空时缓存空字符串，避免同一不存在的 `chunkId` 反复穿透到数据库

这一机制在父子分段检索场景下尤为关键：每条检索结果都可能触发父分段回溯，三级缓存将数据库查询压力降低一个量级。

在文档检索权限体系中，L2 缓存同时缓存权限元数据（`accessibleBy`），检索时在 `EmbeddingSearchRequest` 中注入 `Filter` 条件按当前用户身份过滤，权限变更时通过事件驱动批量失效关联缓存并更新 ES 索引，确保权限变更实时生效。权限过滤与版本过滤（`docVersion`）均通过 ES Filter 条件实现，与三级缓存协同工作，在保障数据安全的同时不影响检索性能。

### 🔥 LLM JSON 输出修复 — 7 步容错管道

LLM 在结构化输出场景中经常产生不符合 JSON 规范的结果（如包裹 Markdown 代码块、使用中文引号、尾部多余逗号等）。`JsonUtil.fixJson()` 实现了 7 步修复管道：
1. **Markdown 代码块提取**：剥离 ` ```json ... ``` ` 包裹
2. **前后垃圾字符移除**：定位首个 `{` / `[` 到末尾 `}` / `]` 之间的有效 JSON
3. **引号修复**：中文引号 → 英文引号，单引号 → 双引号
4. **尾部逗号修复**：移除 `},` / `],` 中的多余逗号
5. **缺失引号修复**：为无引号的键名自动添加双引号
6. **转义字符修复**：清理字符串中的非法换行符、制表符
7. **最终容错**：以上修复后仍无效，将原始文本包装为 `{"content": "..."}` 结构

修复后通过 `ObjectMapper.readTree()` 验证有效性，确保下游代码始终拿到合法 JSON。该工具广泛用于意图识别、查询路由、查询改写等所有依赖 LLM 结构化输出的环节。

### 🔥 工厂模式多策略切片 — DocumentSplitterFactory

`DocumentSplitterFactory` 通过工厂模式封装 5 种切片策略，根据用户选择的 `SplitType` 动态创建对应的 `DocumentSplitter`：

| 切片策略 | 实现类 | 适用场景 |
|---------|--------|--------|
| LENGTH | DocumentByWordSplitter | 纯文本按字数切分 |
| TITLE | MarkdownHeaderParentTextSplitter | Markdown 按标题层级切分，保留父子关系 |
| REGEX | DocumentByRegexSplitter | 自定义正则表达式切分 |
| SEPARATOR | DocumentByRegexSplitter | 按指定分隔符切分 |
| SMART | MarkdownHeaderParentTextSplitter | 智能切分，自动计算 overlap（chunkSize × 10%） |

Excel/CSV 类型文档则独立走 `ExcelSplitter` 处理，不经过工厂模式。前端 `upload.html` 根据选择的切片方式动态显示/隐藏参数组（overlap、titleLevel、regex、separator），实现配置与策略的联动。

### 🔥 查询改写异步回写 — 虚拟线程不阻塞主流程

`KnowEngineQueryTransformer` 在完成查询改写后，通过 `Thread.ofVirtual().name("query-transform-" + chatMessageId).start(...)` 将改写结果异步回写到 `chat_message` 表的 `transformContent` 字段。回写操作与主流程完全解耦：即使回写失败，也不影响改写后的 Query 继续进入检索和生成环节。这一设计使得查询改写的 4 维策略（简洁改写、抽象概念改写、错别字纠正、车型信息标准化）在提升检索质量的同时，对端到端延迟零贡献。

---

## 技术栈

| 分类            | 技术                                        | 说明                      |
|---------------|-------------------------------------------|-------------------------|
| **语言/框架**     | Java 21 + Spring Boot 3.5                 | 虚拟线程、Record 模式匹配        |
| **AI 框架**     | LangChain4j 1.11.0                        | RAG 管道、AI Services、流式对话 |
| **LLM**       | qwen-max-latest、qwen3.5-flash            | 意图识别、查询改写、路由决策、流式生成     |
| **向量存储**      | Elasticsearch                             | KNN / 全文 / 混合检索         |
| **图数据库**      | Neo4j + APOC                              | Text2Cypher 实体关系查询      |
| **关系数据库**     | MySQL + MyBatis-Plus                      | 业务数据 + Text2SQL 结构化查询   |
| **对象存储**      | MinIO                                     | 文档文件存储                  |
| **缓存**        | Redis + Redisson                          | 分布式锁、父分段缓存              |
| **Reranking** | BGE-RERANKER (ONNX Runtime)               | 进程内本地推理，零网络开销           |
| **Embedding** | text-embedding-v4 (通义)                    | 1536 维向量                |
| **文件解析**      | MinerU (PDF)、Apache Tika (类型检测)、EasyExcel | 多格式文档解析                 |
| **任务调度**      | XXL-Job 2.4                               | 补偿任务定时触发                |
| **响应式**       | Project Reactor                           | SSE 流式推送、非阻塞调度          |
| **连接池**       | Druid                                     | SQL 监控、慢查询检测            |

---

## 项目结构

```
know-engine/
├── ai/                          # AI 能力层
│   ├── config/                  #   Memory 配置
│   ├── constant/                #   意图枚举
│   ├── model/                   #   意图识别结果模型
│   └── service/                 #   意图识别、通用对话、Prompt 路由、标题摘要
├── chat/                        # 对话管理层
│   ├── constant/                #   会话状态、消息类型、检索来源
│   ├── controller/              #   对话/会话/消息 REST 接口
│   ├── entity/                  #   会话与消息实体
│   ├── mapper/                  #   MyBatis-Plus Mapper
│   └── service/                 #   对话应用服务（RAG 管道编排）
├── config/                      # 全局配置
│   ├── AsyncConfig              #   线程池配置
│   ├── MyMetaObjectHandler      #   自动填充时间戳
│   ├── MybatisPlusConfig        #   分页插件、乐观锁插件
│   └── XxlJobConfig             #   XXL-Job 执行器配置
├── document/                    # 知识文档管理层
│   ├── config/                  #   MinIO 配置
│   ├── constant/                #   文档状态、文件类型、知识库类型
│   ├── controller/              #   文档/切片 REST 接口
│   ├── entity/                  #   文档与切片实体
│   ├── event/                   #   Spring 事件（Converted/Chunked）
│   ├── job/                     #   XXL-Job 补偿任务
│   ├── mapper/                  #   MyBatis-Plus Mapper
│   ├── service/                 #   文档处理、切片、向量化、文件存储
│   └── util/                    #   文件类型检测 (Tika)
├── infra/                       # 基础设施
│   ├── json/                    #   JSON 工具
│   ├── lock/                    #   分布式锁注解 + AOP 切面
│   └── snowflake/               #   雪花 ID 生成器
└── rag/                         # RAG 检索增强层
    ├── config/                   #   ES / Neo4j 配置
    ├── constant/                 #   元数据 Key 常量
    ├── controller/               #   检索测试接口
    ├── model/                    #   路由结果模型
    └── modules/                  #   RAG 核心模块
        ├── KnowEngineQueryTransformer   # 查询改写器
        ├── KnowEngineQueryRouter        # 三源路由器
        ├── KnowEngineElasticsearchContentRetriever  # ES 检索器（父子分段扩展）
        ├── ProgressAwareContentAggregator          # 进度感知聚合器
        ├── reranker/              #   BGE-RERANKER 本地单例
        └── splitter/              #   文档切片器（Markdown/Excel）
```

---

## 快速开始

### 环境依赖

- JDK 21+
- MySQL 8.0+
- Redis 7.0+
- Elasticsearch 8.x（需开启 KNN 向量检索）
- Neo4j 5.x（需安装 APOC 插件）
- MinIO
- XXL-Job Admin 2.4+

### 配置

1. 修改 `src/main/resources/application.yml`，填入各中间件连接信息和 LLM API Key
2. 执行 `src/main/resources/sql/tables.sql` 初始化数据库表

### 构建 & 运行

```bash
mvn clean package -DskipTests
java -jar target/know-engine-1.0.0-SNAPSHOT.jar
```

### 接口速览

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat/send` | POST | 流式对话（SSE），支持进度推送和 RAG 引用溯源 |
| `/chat/list` | GET | 查询用户会话列表 |
| `/chat/messages` | GET | 查询会话消息历史 |
| `/api/document/upload` | POST | 上传知识文档 |
| `/api/document/split/{id}` | POST | 手动触发文档切片 |
| `/api/document/embedding` | POST | 手动触发向量化 |

---

## 设计原则

- **事件驱动优于直接调用**：文档处理流程通过 Spring Event 解耦，Controller 瘦身，职责清晰；权限变更同样通过事件驱动批量更新 ES 索引；权限变更同样通过事件驱动批量更新 ES 索引
- **最终一致性**：事件驱动 + XXL-Job 补偿，“异步优先，定时兜底”
- **流式优先**：全链路 Reactor 响应式，SSE 逐 Token 推送，进度可见；多渠道输出（Web/钉钉）共享同一流式管道；多渠道输出（Web/钉钉）共享同一流式管道
- **声明式并发控制**：`@DistributeLock` 注解一行搞定幂等保护
- **本地推理优先**：Reranking 在 JVM 进程内完成，省去网络往返，降低延迟
- **虚拟线程优先**：所有 LLM 辅助调用（标题生成、查询改写回写）均使用虚拟线程，零阻塞主流程
- **容错优先**：LLM JSON 输出修复管道、缓存空值防击穿、标题生成失败保留临时标题、Text2Cypher 降级回退 ES，处处留有余地
- **分布式 ID 全链路**：雪花算法生成的 ChunkId 贯穿切片 → 存储 → 检索 → 扩展，全局唯一可追溯；版本链路通过 `parentVersionDocId` 关联，时序可追溯
- **权限内建**：权限信息从文档写入切片 metadata → ES 索引 → 检索 Filter，全链路内建，变更实时生效
---

