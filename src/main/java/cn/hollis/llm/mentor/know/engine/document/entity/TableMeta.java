package cn.hollis.llm.mentor.know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表元数据实体类
 * 用于存储动态创建的表的元数据信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("table_meta")
public class TableMeta extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 表名
     */
    @TableField("table_name")
    private String tableName;

    /**
     * 表描述
     */
    @TableField("description")
    private String description;

    /**
     * 建表语句
     */
    @TableField("create_sql")
    private String createSql;

    /**
     * 字段信息（JSON格式）
     */
    @TableField("columns_info")
    private String columnsInfo;
}
