package cn.hollis.llm.mentor.know.engine.document.mapper;

import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段表 Mapper 接口
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {
}
