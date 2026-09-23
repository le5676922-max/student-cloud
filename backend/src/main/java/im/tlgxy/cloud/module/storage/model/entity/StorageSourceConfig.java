package im.tlgxy.cloud.module.storage.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 存储源扩展属性实体类
 * <p>
 * 对应数据库表 storage_source_config，用于存储存储源的扩展配置参数。
 * 每个存储源（StorageSource）可以关联多条配置记录，每条记录以键值对（name-value）的形式
 * 存储一个参数。这种设计使得不同存储类型可以有不同的参数集合，而无需修改表结构。
 * </p>
 * <p>
 * 例如：阿里云 OSS 存储源需要 accessKey、secretKey、bucketName 等参数，
 * 每个参数对应此表中的一条记录。通过 storageId 外键与 storage_source 表关联。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "存储源拓展属性")
@TableName(value = "storage_source_config")
public class StorageSourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /** 参数名称（键），用于标识配置项，如 "accessKey"、"bucketName" */
    @TableField(value = "`name`")
    @Schema(title = "存储源属性名称 name", example = "bucketName")
    private String name;

    /** 存储源类型，用于区分不同存储类型的配置项 */
    @TableField(value = "`type`")
    @Schema(title = "存储源类型")
    private StorageTypeEnum type;

    /** 参数标题，用于前端表单项的标签显示，如 "Bucket 名称" */
    @TableField(value = "title")
    @Schema(title = "存储源属性名称", example = "Bucket 名称")
    private String title;

    /** 关联的存储源 ID，外键指向 storage_source 表 */
    @TableField(value = "storage_id")
    @Schema(title = "存储源 id", example = "1")
    private Integer storageId;

    /** 参数的值，存储用户填写的配置内容 */
    @TableField(value = "`value`")
    @Schema(title = "存储源对应的值", example = "my-bucket")
    private String value;

}