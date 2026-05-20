package cn.hollis.llm.mentor.know.engine.document.constant;

public enum SegmentStatus {
    /**
     * 关系型数据库存储完成
     */
    STORED,
    /**
     * 向量数据库存储完成（非必须）
     */
    VECTOR_STORED;
}
