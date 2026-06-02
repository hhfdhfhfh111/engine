package cn.hollis.llm.mentor.know.engine.document.service.impl;

import cn.hollis.llm.mentor.know.engine.document.constant.DocumentStatus;
import cn.hollis.llm.mentor.know.engine.document.constant.FileType;
import cn.hollis.llm.mentor.know.engine.document.constant.KnowledgeBaseType;
import cn.hollis.llm.mentor.know.engine.document.constant.SegmentStatus;
import cn.hollis.llm.mentor.know.engine.document.entity.DocumentSplitParam;
import cn.hollis.llm.mentor.know.engine.document.entity.DocumentUploadParam;
import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeDocument;
import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeSegment;
import cn.hollis.llm.mentor.know.engine.document.event.DocumentChunkedEvent;
import cn.hollis.llm.mentor.know.engine.document.event.DocumentConvertedEvent;
import cn.hollis.llm.mentor.know.engine.document.service.*;
import cn.hollis.llm.mentor.know.engine.document.util.FileTypeUtil;
import cn.hollis.llm.mentor.know.engine.infra.lock.DistributeLock;
import cn.hollis.llm.mentor.know.engine.rag.constant.MetadataKeyConstant;
import cn.hollis.llm.mentor.know.engine.rag.modules.splitter.DocumentSplitterFactory;
import cn.hollis.llm.mentor.know.engine.rag.modules.splitter.ExcelSplitter;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.base.Stopwatch;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理服务实现类
 * 负责文档的业务流程处理：上传、转换、分段、向量化
 */
@Slf4j
@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    /** 文档元数据 CRUD 服务 */
    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    /** MinIO 文件存储服务 */
    @Autowired
    private FileStorageService fileStorageService;

    /** 文件处理策略工厂，按文件类型/知识库类型路由不同的处理逻辑 */
    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    /** 文档分段 CRUD 服务 */
    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    /** Elasticsearch 向量存储 */
    @Autowired
    private ElasticsearchEmbeddingStore elasticsearchEmbeddingStore;

    /** OpenAI 兼容的 Embedding 模型 */
    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    /** Spring 事件发布器，用于文档处理各阶段的异步通知 */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** MinIO 桶名 */
    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 文档上传处理流程：
     * 1. 将原始文件上传到 MinIO
     * 2. 创建文档元数据记录（状态：UPLOADED）
     * 3. 根据文件类型/知识库类型路由到对应的 FileProcessService 做预处理（如格式转换）
     * 4. 根据知识库类型更新文档最终状态（CONVERTED / STORED）
     * <p>
     * 使用分布式锁防止同一用户并发上传冲突。
     */
    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#documentUploadParam.uploadUser", waitTime = 0)
    public KnowledgeDocument upload(DocumentUploadParam documentUploadParam) throws IOException {
        try {
            log.info("start to upload ....");
            String fileName = documentUploadParam.file().getOriginalFilename();

            // Step 1: 上传原始文件到 MinIO
            String fileUrl = fileStorageService.uploadFile(documentUploadParam.file(), fileName);

            // Step 2: 构建文档记录并持久化
            KnowledgeDocument document = new KnowledgeDocument();
            document.setDocTitle(documentUploadParam.title());
            document.setUploadUser(documentUploadParam.uploadUser());
            document.setDocUrl(fileUrl);
            document.setStatus(DocumentStatus.UPLOADED);
            document.setAccessibleBy(documentUploadParam.accessibleBy());
            document.setDescription(documentUploadParam.description());
            document.setKnowledgeBaseType(KnowledgeBaseType.valueOf(documentUploadParam.knowledgeBaseType()));
            document.setTableName(documentUploadParam.tableName());

            boolean result = knowledgeDocumentService.save(document);
            Assert.isTrue(result, "文件上传失败");

            // Step 3: 按文件类型/知识库类型分发到对应的文件处理服务（如 PDF→Markdown 转换）
            FileProcessService fileProcessService = fileProcessServiceFactory.get(
                    FileTypeUtil.getFileType(fileName, documentUploadParam.file()),
                    document.getKnowledgeBaseType());
            if (fileProcessService != null) {
                fileProcessService.processDocument(document, documentUploadParam.file().getInputStream());
            }

            // Step 4: 更新文档状态
            if (document.getKnowledgeBaseType() == KnowledgeBaseType.DOCUMENT_SEARCH) {
                // 文档检索模式：文件需要经过转换 → 分段 → 向量化流程
                // 若 FileProcessService 已写入 convertedDocUrl（如 MinerU 解析后的 md），则保留不覆盖
                if (document.getConvertedDocUrl() == null) {
                    document.setConvertedDocUrl(fileUrl);
                }
                if (document.getStatus() == DocumentStatus.UPLOADED) {
                    document.setStatus(DocumentStatus.CONVERTED);
                }
                result = knowledgeDocumentService.updateById(document);
                Assert.isTrue(result, "文件状态更新失败");
            } else {
                // 非文档检索模式：直接标记为已存储，无需后续分段/向量化
                document.setStatus(DocumentStatus.STORED);
                document.setConvertedDocUrl(fileUrl);
                result = knowledgeDocumentService.updateById(document);
                Assert.isTrue(result, "文件状态更新失败");
            }
            return document;
        } catch (Exception e) {
            throw new IOException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 文档分段处理流程：
     * 1. 校验文档状态（必须是 CONVERTED）
     * 2. 从 MinIO 下载已转换的文档内容
     * 3. 根据文件类型选择分段器（Excel/CSV 走 ExcelSplitter，其余走工厂策略）
     * 4. 将分段结果转换为 KnowledgeSegment 并批量持久化
     * 5. 更新文档状态为 CHUNKED，发布分段完成事件
     * <p>
     * 幂等处理：若文档已处于 CHUNKED 状态，直接返回已有分段数量。
     * 使用分布式锁防止同一文档并发分段。
     *
     * @param document          文档实体
     * @param documentSplitParam 分段参数（策略、大小、重叠等）
     * @return 分段数量
     */
    @Override
    @Transactional
    @DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)
    public int split(KnowledgeDocument document, DocumentSplitParam documentSplitParam) {
        // Step 1: 校验文档状态
        Assert.notNull(document, "文档不存在");
        Assert.notNull(document.getConvertedDocUrl(), "文档未转换完成");

        // 幂等：已分段则直接返回已有分段数
        if (document.getStatus() == DocumentStatus.CHUNKED) {
            Long chunkedCount = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                    .eq("document_id", document.getDocId())
                    .eq("skipEmbedding", 0));
            return chunkedCount.intValue();
        }

        if (document.getStatus() != DocumentStatus.CONVERTED) {
            throw new RuntimeException("文档状态不为CONVERTED，无法完成切分");
        }

        // Step 2: 从 MinIO 下载已转换后的文档内容
        String convertedDocUrl = document.getConvertedDocUrl();
        String objectName = extractObjectNameFromUrl(convertedDocUrl);
        Assert.notNull(objectName, "无法解析文档URL");

        // Step 3: 按文件类型选择分段策略
        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {
            if (FileType.EXCEL == FileTypeUtil.getFileType(document.getConvertedDocUrl())
                    || FileType.CSV == FileTypeUtil.getFileType(document.getConvertedDocUrl())) {
                // Excel/CSV 使用专用分段器，按行/按表分段
                ExcelSplitter splitter = new ExcelSplitter(documentSplitParam.chunkSize(), false);
                segments = splitter.split(inputStream.readAllBytes());
            } else {
                // Markdown/文本等使用工厂创建的通用分段器
                DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
                Document doc = Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                segments = splitter.split(doc);
            }
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }

        // Step 4: 将分段转换为 KnowledgeSegment 实体，填充元数据
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(segment.text());
            knowledgeSegment.setChunkId(segment.metadata().getString(MetadataKeyConstant.CHUNK_ID));
            Metadata metadata = segment.metadata();
            metadata.put(MetadataKeyConstant.DOC_ID, document.getDocId());
            metadata.put(MetadataKeyConstant.FILE_NAME, document.getDocTitle());
            metadata.put(MetadataKeyConstant.URL, document.getDocUrl());

            // todo: metadata 统一处理（权限相关、多版本相关）
            knowledgeSegment.setMetadata(JSON.toJSONString(metadata.toMap()));
            knowledgeSegment.setDocumentId(document.getDocId());
            knowledgeSegment.setChunkOrder(i);

            // 根据分段器标记决定是否跳过向量嵌入（如表头行等）
            Integer skipEmbedding = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && skipEmbedding == 1) {
                knowledgeSegment.setSkipEmbedding(1);
            } else {
                knowledgeSegment.setSkipEmbedding(0);
            }
            knowledgeSegment.setStatus(SegmentStatus.STORED);

            knowledgeSegments.add(knowledgeSegment);
        }

        // Step 5: 批量保存分段并更新文档状态
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean saveResult = knowledgeSegmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveResult, "保存知识片段失败");
        log.info("保存知识片段耗时: {} ms", stopwatch.elapsed().toMillis());

        int segmentCount = knowledgeSegments.size();

        document.setStatus(DocumentStatus.CHUNKED);
        boolean updateResult = knowledgeDocumentService.updateById(document);
        Assert.isTrue(updateResult, "更新文档状态失败");

        // Step 6: 发布文档已分段事件，触发下游（如自动向量化）
        publishChunkedEvent(document, segmentCount);

        return segmentCount;
    }

    /**
     * 向量嵌入与存储流程：
     * 1. 分页拉取文档下所有待嵌入的 KnowledgeSegment（状态 STORED 且未嵌入）
     * 2. 调用 Embedding 模型将分段文本转为向量
     * 3. 将向量存入 Elasticsearch
     * 4. 回写 embeddingId 并更新分段状态为 VECTOR_STORED
     * 5. 全部成功后更新文档状态为 VECTOR_STORED
     * <p>
     * 幂等处理：文档已是 VECTOR_STORED 直接返回 true。
     * 使用分页遍历避免大数据量内存溢出，每批 100 条。
     * 最后 double check 确保没有遗漏的分段。
     *
     * @param document 文档实体
     * @return true-全部嵌入完成，false-存在未成功嵌入的分段
     */
    @Override
    @DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)
    public boolean embedAndStore(KnowledgeDocument document) {
        if (document == null) {
            return false;
        }

        // 幂等：已向量化则直接返回成功
        if (document.getStatus() == DocumentStatus.VECTOR_STORED) {
            return true;
        }

        // 只有 CHUNKED 状态的文档才能进行向量化
        if (document.getStatus() != DocumentStatus.CHUNKED) {
            return false;
        }

        // 构建查询条件：当前文档下、状态为 STORED、尚未嵌入、且不跳过嵌入的分段
        LambdaQueryWrapper<KnowledgeSegment> queryWrapper = Wrappers.<KnowledgeSegment>lambdaQuery()
                .eq(KnowledgeSegment::getDocumentId, document.getDocId())
                .eq(KnowledgeSegment::getStatus, SegmentStatus.STORED)
                .isNull(KnowledgeSegment::getEmbeddingId)
                .eq(KnowledgeSegment::getSkipEmbedding, 0);

        // 分页遍历，每批 100 条
        Page<KnowledgeSegment> page = knowledgeSegmentService.page(new Page<>(1, 100), queryWrapper);

        while (page.getCurrent() == 1 || page.hasNext()) {
            List<KnowledgeSegment> textSegmentsToEmbed = page.getRecords();

            // Step 2: 将分段转为 LangChain4j TextSegment，调用 Embedding 模型
            List<TextSegment> textSegments = textSegmentsToEmbed.stream()
                    .map(segment -> TextSegment.from(segment.getText(), Metadata.from(segment.getMetadataMap())))
                    .toList();
            Response<List<Embedding>> embeddingResponse = openAiEmbeddingModel.embedAll(textSegments);

            // Step 3: 将向量批量写入 Elasticsearch
            List<String> embeddingIds = elasticsearchEmbeddingStore.addAll(embeddingResponse.content(), textSegments);

            // todo: 事务处理 — embedding 写入 ES 和 DB 状态更新应保证一致性

            // Step 4: 回写 embeddingId 并更新分段状态
            for (int i = 0; i < textSegmentsToEmbed.size(); i++) {
                String embeddingId = embeddingIds.get(i);
                KnowledgeSegment knowledgeSegment = textSegmentsToEmbed.get(i);
                knowledgeSegment.setEmbeddingId(embeddingId);
                knowledgeSegment.setStatus(SegmentStatus.VECTOR_STORED);
                knowledgeSegmentService.updateById(knowledgeSegment);
            }

            // 翻页继续处理
            page = knowledgeSegmentService.page(new Page<>(page.getCurrent() + 1, 100), queryWrapper);
        }

        // Step 5: double check — 确认无遗漏分段后，更新文档状态
        long segmentCount = knowledgeSegmentService.count(queryWrapper);
        if (segmentCount == 0) {
            document.setStatus(DocumentStatus.VECTOR_STORED);
            return knowledgeDocumentService.updateById(document);
        }

        log.warn("向量存储失败，存在部分分段没有存储成功，未成功的数量：{}", segmentCount);
        return false;
    }

    // ==================== 事件发布方法 ====================

    /**
     * 发送文档已转换事件
     *
     * @Deprecated 不再使用事件驱动，靠用户在前端手动触发分段，因为需要用户选择分段方式。
     */
    @Deprecated
    private void publishConvertedEvent(KnowledgeDocument document) {
        log.info("发送文档CONVERTED事件，documentId: {}", document.getDocId());
        DocumentConvertedEvent event = new DocumentConvertedEvent(this, document.getDocId(), document);
        eventPublisher.publishEvent(event);
    }

    /**
     * 发送文档已分段事件
     */
    private void publishChunkedEvent(KnowledgeDocument document, int segmentCount) {
        log.info("发送文档CHUNKED事件，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
        DocumentChunkedEvent event = new DocumentChunkedEvent(this, document.getDocId(), document, segmentCount);
        eventPublisher.publishEvent(event);
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过后缀名判断是否为 PDF 文件
     */
    private boolean isPdfFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".pdf");
    }

    /**
     * 通过 Apache Tika 检测文件内容类型判断是否为 PDF 文件
     */
    private boolean isPdfContent(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = new Tika().detect(is);
            return "application/pdf".equals(mimeType);
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从MinIO URL中提取对象名称
     */
    private String extractObjectNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // URL格式: http://endpoint/bucketName/objectName
        int lastSlashIndex = url.lastIndexOf(bucketName) + bucketName.length();
        if (lastSlashIndex == -1 || lastSlashIndex == url.length() - 1) {
            return null;
        }
        return url.substring(lastSlashIndex + 1);
    }
}
