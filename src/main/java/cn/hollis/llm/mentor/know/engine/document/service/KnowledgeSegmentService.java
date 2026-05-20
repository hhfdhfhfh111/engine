package cn.hollis.llm.mentor.know.engine.document.service;

import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.Serializable;

/**
 * 知识片段表 Service 接口
 */
public interface KnowledgeSegmentService extends IService<KnowledgeSegment> {

    public String getTextByChunkId(Serializable chunkId);
}
