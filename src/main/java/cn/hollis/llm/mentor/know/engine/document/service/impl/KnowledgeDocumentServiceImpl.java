package cn.hollis.llm.mentor.know.engine.document.service.impl;

import cn.hollis.llm.mentor.know.engine.document.entity.KnowledgeDocument;
import cn.hollis.llm.mentor.know.engine.document.mapper.KnowledgeDocumentMapper;
import cn.hollis.llm.mentor.know.engine.document.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 知识文档表 Service 实现类
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {

}
