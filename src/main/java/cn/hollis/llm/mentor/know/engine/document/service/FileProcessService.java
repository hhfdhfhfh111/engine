package cn.hollis.llm.mentor.know.engine.document.service;

import cn.hollis.llm.mentor.know.engine.document.constant.FileType;
import cn.hollis.llm.mentor.know.engine.document.constant.KnowledgeBaseType;
import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeDocument;

import java.io.File;
import java.io.InputStream;

/**
 * 文件处理服务 - 负责文档转换处理
 */
public interface FileProcessService {
    /**
     * 处理文档转换 - Markdown 格式
     * 1. 从 MinIO 下载文件
     * 2. 调用文档解析接口获取md/zip
     * 3. 转换后的文档保存在minio上
     * 3. 更新文档状态和转换后的 URL
     *
     * @param document 文档对象
     */
    public void processDocument(KnowledgeDocument document, InputStream inputStream);

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType);
}

