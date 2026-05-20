package cn.hollis.llm.mentor.know.engine.document.entity;

import org.springframework.web.multipart.MultipartFile;

/**
 * @author Hollis
 * @param file
 * @param uploadUser
 * @param title
 * @param accessibleBy
 * @param description
 * @param knowledgeBaseType
 */
public record DocumentUploadParam(MultipartFile file, String uploadUser, String title, String accessibleBy,
                                  String description, String knowledgeBaseType,String tableName) {
}
