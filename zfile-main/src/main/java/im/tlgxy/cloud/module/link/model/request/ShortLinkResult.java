package im.tlgxy.cloud.module.link.model.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 短链结果 DTO 类
 * <p>
 * 用于封装返回给前端的短链信息，包含短链的基本属性以及关联的存储源信息。
 * 相比 {@link im.tlgxy.cloud.module.link.model.entity.ShortLink} 实体类，
 * 该类增加了存储源名称、存储源类型描述和完整的短链地址等展示字段。
 * <p>
 * 存储源类型描述通过 {@link #getStorageTypeStr()} 方法动态获取，
 * 该字段在 JSON 序列化时被忽略（{@link JsonIgnore}），避免重复序列化。
 *
 * @author zhaojun
 */
@Data
public class ShortLinkResult {

    /**
     * 短链 ID
     */
    @Schema(title = "短链 id", example = "1")
    private Integer id;

    /**
     * 存储源名称
     */
    @Schema(title = "存储源名称", example = "我的本地存储")
    private String storageName;

    /**
     * 存储源类型枚举
     */
    @Schema(title = "存储源类型")
    private StorageTypeEnum storageType;

    /**
     * 存储源类型描述字符串（如"本地存储"、"阿里云OSS"等），从 storageType 动态获取。
     * 该字段不参与 JSON 序列化，仅在 Excel 导出时使用。
     */
    @JsonIgnore
    private String storageTypeStr;

    /**
     * 获取存储源类型的描述文本
     *
     * @return 存储源类型的中文描述
     */
    public String getStorageTypeStr() {
        return storageType.getDescription();
    }

    /**
     * 短链 key，6 位随机字符串
     */
    @Schema(title = "短链 key", example = "voldd3")
    private String shortKey;

    /**
     * 原始文件路径
     */
    @Schema(title = "文件 url", example = "/directlink/1/test02.png")
    private String url;

    /**
     * 短链创建时间
     */
    @Schema(title = "创建时间", example = "2021-11-22 10:05")
    private Date createDate;

    /**
     * 短链过期时间
     */
    @Schema(title = "过期时间", example = "2021-11-23 10:05")
    private Date expireDate;

    /**
     * 完整的短链地址（如 http://example.com/s/voldd3），由后端动态拼接
     */
    @Schema(title="短链地址")
    private String shortLink;

}