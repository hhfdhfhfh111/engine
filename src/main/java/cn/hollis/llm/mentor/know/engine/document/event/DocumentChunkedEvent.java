package cn.hollis.llm.mentor.know.engine.document.event;

import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeDocument;
import org.springframework.context.ApplicationEvent;

/**
 * 文档已分段事件
 * 当文档状态变更为CHUNKED时发送此事件
 */
public class DocumentChunkedEvent extends ApplicationEvent {

    /**
     * 文档ID
     */
    private final Long documentId;

    /**
     * 分段数量
     */
    private final int segmentCount;

    public DocumentChunkedEvent(Object source, Long documentId, KnowledgeDocument document, int segmentCount) {
        super(source);
        this.documentId = documentId;
        this.segmentCount = segmentCount;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    @Override
    public String toString() {
        return "DocumentChunkedEvent{documentId=" + documentId + ", segmentCount=" + segmentCount + '}';
    }
}
