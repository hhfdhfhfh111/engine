package cn.hollis.llm.mentor.know.engine.document.entity;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传参数，封装上传文件及知识库配置信息
 *
 * @param file               上传的文件
 * @param uploadUser         上传用户
 * @param title              文档标题
 * @param accessibleBy       可见范围
 * @param description        文档描述
 * @param knowledgeBaseType  知识库类型
 * @param tableName          关联数据表名（结构化知识库时使用）
 */
public record DocumentUploadParam(MultipartFile file, String uploadUser, String title, String accessibleBy,
                                  String description, String knowledgeBaseType,String tableName) {
}
