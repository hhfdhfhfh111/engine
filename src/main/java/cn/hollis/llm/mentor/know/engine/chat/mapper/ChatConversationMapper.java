package cn.hollis.llm.mentor.know.engine.chat.mapper;

import cn.hollis.llm.mentor.know.engine.chat.entity.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话会话表 Mapper
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
