package im.tlgxy.cloud.module.link.model.entity;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 短链信息实体类
 * <p>
 * 对应数据库中的 short_link 表，存储每条短链的完整信息。
 * 每条记录包含短链的唯一标识 key、关联的存储源 ID、原始文件路径、
 * 创建时间和过期时间等核心字段。
 * <p>
 * 短链有两种类型：
 * <ul>
 *   <li>永久短链：过期时间为 9999-12-31，不会自动过期</li>
 *   <li>临时短链：在指定的过期时间后自动失效</li>
 * </ul>
 * <p>
 * 使用 MyBatis-Plus 注解进行 ORM 映射，使用 Lombok {@link Data} 自动生成 getter/setter 方法。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "短链信息")
@TableName(value = "short_link")
public class ShortLink implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 永久直链失效时间为 -1（用于 expire_time 参数）
     */
    public static final Long PERMANENT_EXPIRE_TIME = -1L;

    /**
     * 永久直链失效日期为 9999-12-31（用于 expire_date 数据库字段）
     */
    public static final Date PERMANENT_EXPIRE_DATE = DateUtil.parseDate("9999-12-31");

    /**
     * 主键 ID，数据库自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /**
     * 存储源 ID，关联 storage_source 表，标识该短链属于哪个存储源
     */
    @TableField(value = "storage_id")
    @Schema(title = "存储源 ID", example = "1")
    private Integer storageId;

    /**
     * 短链 key，由系统随机生成的 6 位唯一字符串，用于构建短链 URL（如 /s/voldd3）
     */
    @TableField(value = "short_key")
    @Schema(title = "短链 key", example = "voldd3")
    private String shortKey;

    /**
     * 原始文件路径，短链对应的真实文件在存储源中的路径
     */
    @TableField(value = "url")
    @Schema(title = "短链 url", example = "/directlink/1/test02.png")
    private String url;

    /**
     * 创建时间，记录短链生成的时间
     */
    @TableField(value = "create_date")
    @Schema(title = "创建时间", example = "2021-11-22 10:05")
    private Date createDate;

    /**
     * 过期时间，超过该时间后短链将失效
     */
    @TableField(value = "expire_date")
    @Schema(title = "过期时间", example = "2021-11-23 10:05")
    private Date expireDate;

}