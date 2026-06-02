package cn.hollis.llm.mentor.know.engine.rag.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 查询路由结果
 * 包含意图分类、推荐查询策略及置信度评分
 *
 * @param intent      用户问题核心意图（relational_db / graph_db / knowledge_base）
 * @param strategy    推荐的查询策略
 * @param confidence  策略推荐的置信度（0–1）
 * @param reasoning   推理理由
 */
public record QueryRouteResult(
        @JsonPropertyDescription("用户问题的核心意图，仅使用以下三个字符串值：relational_db、graph_db、knowledge_base") String intent,
        @JsonPropertyDescription("推荐的查询策略") String strategy,
        @JsonPropertyDescription("策略推荐的置信度（0–1），评分保留两位小数") double confidence,
        @JsonPropertyDescription("推理理由") String reasoning) {
}
