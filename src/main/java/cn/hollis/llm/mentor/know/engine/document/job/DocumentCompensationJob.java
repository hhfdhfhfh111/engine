package cn.hollis.llm.mentor.know.engine.document.job;

import cn.hollis.llm.mentor.know.engine.document.constant.DocumentStatus;
import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeDocument;
import cn.hollis.llm.mentor.know.engine.document.service.DocumentProcessService;
import cn.hollis.llm.mentor.know.engine.document.service.KnowledgeDocumentService;
import cn.hollis.llm.mentor.know.engine.document.service.KnowledgeSegmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档处理补偿任务
 * 用于处理事件处理失败后的补偿逻辑
 */
@Slf4j
@Component
public class DocumentCompensationJob {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private DocumentProcessService documentProcessService;

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * 补偿阈值（分钟）- 超过此时间才进行补偿
     */
    private static final int COMPENSATION_THRESHOLD_MINUTES = 5;

    /**
     * 文档分段补偿任务
     * 扫描 CONVERTED 状态超过阈值的文档，重新触发分段
     * @Deprecated 不再需要，靠用户在前端手动触发分段，因为需要用户选择分段方式。
     */
//    @Deprecated
//    @XxlJob("documentSplitCompensation")
//    public void documentSplitCompensation() {
//        log.info("========== 开始执行文档分段补偿任务 ==========");
//        int successCount = 0;
//        int failCount = 0;
//
//        try {
//            // 这里简化处理，查询所有 CONVERTED 状态的文档
//            LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
//            queryWrapper.eq(KnowledgeDocument::getStatus, DocumentStatus.CONVERTED);
//            queryWrapper.isNotNull(KnowledgeDocument::getConvertedDocUrl);
//
//            List<KnowledgeDocument> documents = knowledgeDocumentService.list(queryWrapper);
//            log.info("发现 {} 个待补偿的 CONVERTED 状态文档", documents.size());
//
//            for (KnowledgeDocument document : documents) {
//                try {
//                    // 检查重试次数（从 extension 字段解析，或使用默认值）
//                    int retryCount = getRetryCount(document);
//                    if (retryCount >= MAX_RETRY_COUNT) {
//                        log.warn("文档 {} 已达最大重试次数 {}，跳过补偿", document.getDocId(), retryCount);
//                        continue;
//                    }
//
//                    log.info("补偿处理文档分段，documentId: {}, retryCount: {}", document.getDocId(), retryCount);
//
//                    // 执行分段
//                    int segmentCount = documentProcessService.split(document);
//
//                    // 更新重试次数
//                    updateRetryCount(document.getDocId(), retryCount + 1);
//
//                    log.info("文档分段补偿成功，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
//                    successCount++;
//                } catch (Exception e) {
//                    log.error("文档分段补偿失败，documentId: {}", document.getDocId(), e);
//                    failCount++;
//                }
//            }
//        } catch (Exception e) {
//            log.error("文档分段补偿任务执行异常", e);
//        }
//
//        log.info("========== 文档分段补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
//    }

    /**
     * 向量化补偿任务
     * 扫描 CHUNKED 状态但存在未向量化的 segment，重新触发向量化
     */
    @XxlJob("documentEmbeddingCompensation")
    public void documentEmbeddingCompensation() {
        log.info("========== 开始执行向量化补偿任务 ==========");
        int successCount = 0;
        int failCount = 0;

        try {
            // 查询 CHUNKED 状态的文档
            LambdaQueryWrapper<KnowledgeDocument> docQueryWrapper = new LambdaQueryWrapper<>();
            docQueryWrapper.eq(KnowledgeDocument::getStatus, DocumentStatus.CHUNKED);

            List<KnowledgeDocument> documents = knowledgeDocumentService.list(docQueryWrapper);
            log.info("发现 {} 个 CHUNKED 状态的文档", documents.size());

            for (KnowledgeDocument document : documents) {
                try {
                    // 检查重试次数
                    int retryCount = getRetryCount(document);
                    if (retryCount >= MAX_RETRY_COUNT) {
                        log.warn("文档 {} 已达最大重试次数 {}，跳过补偿", document.getDocId(), retryCount);
                        continue;
                    }

                    // 执行向量化
                    boolean success = documentProcessService.embedAndStore(document);

                    if (success) {
                        // 更新重试次数
                        updateRetryCount(document.getDocId(), retryCount + 1);
                        log.info("向量化补偿成功，documentId: {}", document.getDocId());
                        successCount++;
                    } else {
                        log.warn("向量化补偿失败，documentId: {}", document.getDocId());
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("向量化补偿失败，documentId: {}", document.getDocId(), e);
                    failCount++;
                }
            }
        } catch (Exception e) {
            log.error("向量化补偿任务执行异常", e);
        }

        log.info("========== 向量化补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
    }

    /**
     * 从 extension 字段获取重试次数
     */
    private int getRetryCount(KnowledgeDocument document) {
        String extension = document.getExtension();
        if (extension == null || extension.isEmpty()) {
            return 0;
        }
        try {
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(extension);
            return json.getIntValue("retryCount");
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 更新重试次数到 extension 字段
     */
    private void updateRetryCount(Long documentId, int retryCount) {
        KnowledgeDocument document = knowledgeDocumentService.getById(documentId);
        if (document == null) {
            return;
        }

        com.alibaba.fastjson2.JSONObject json;
        String extension = document.getExtension();
        if (extension == null || extension.isEmpty()) {
            json = new com.alibaba.fastjson2.JSONObject();
        } else {
            try {
                json = com.alibaba.fastjson2.JSON.parseObject(extension);
            } catch (Exception e) {
                json = new com.alibaba.fastjson2.JSONObject();
            }
        }

        json.put("retryCount", retryCount);
        json.put("lastRetryTime", LocalDateTime.now().toString());

        document.setExtension(json.toJSONString());
        knowledgeDocumentService.updateById(document);
    }
}
