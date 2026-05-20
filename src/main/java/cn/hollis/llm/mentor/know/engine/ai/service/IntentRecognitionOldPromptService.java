package cn.hollis.llm.mentor.know.engine.ai.service;

import cn.hollis.llm.mentor.know.engine.ai.model.IntentRecognitionResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * 意图识别服务 - 使用旧版提示词（用于对比测试）
 *
 * @author Hollis
 */
@AiService
public interface IntentRecognitionOldPromptService {

    @SystemMessage(fromResource = "prompts/intent-recognition-old-prompt.txt")
    IntentRecognitionResult chat(@UserMessage String userMessage);
}
