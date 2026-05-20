package cn.hollis.llm.mentor.know.engine.chat.entity;

import cn.hollis.llm.mentor.know.engine.ai.model.IntentRecognitionResult;

public record ChatParam(String userId, String conversationId, String messageId,String content,String assistantMessageId,
                        IntentRecognitionResult intentRecognitionResult) {
}
