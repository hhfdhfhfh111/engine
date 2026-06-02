package cn.hollis.llm.mentor.know.engine.ai.service;

import cn.hollis.llm.mentor.know.engine.ai.model.IntentRecognitionResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * 意图识别服务
 * 基于 LangChain4j AiService 实现，通过系统提示词自动识别用户意图
 */
public interface IntentRecognitionService {

    @SystemMessage(fromResource = "prompts/intent-recognition-new-prompt.txt")
    IntentRecognitionResult chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
