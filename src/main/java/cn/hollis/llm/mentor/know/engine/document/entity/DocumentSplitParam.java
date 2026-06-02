package cn.hollis.llm.mentor.know.engine.document.entity;

/**
 */
public record DocumentSplitParam(String splitType,
                                 Integer chunkSize,
                                 Integer overlap,
                                 Integer titleLevel,
                                 String separator,
                                 String regex) {
}
