package cn.hollis.llm.mentor.know.engine.document.entity;

/**
 * 文档分段参数，封装分段策略及相关配置项
 */
public record DocumentSplitParam(String splitType,
                                 Integer chunkSize,
                                 Integer overlap,
                                 Integer titleLevel,
                                 String separator,
                                 String regex) {
}
