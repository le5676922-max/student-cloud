package im.tlgxy.cloud.module.storage.model.result;

import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 存储源基本信息响应类
 * <p>
 * 封装前端展示存储源列表时需要的简要信息。
 * 包含存储源名称、别名（key）、类型、搜索开关和图片模式开关。
 * 实现了 Serializable 接口以支持序列化缓存。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "存储源基本信息响应类")
public class StorageSourceResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 存储源名称，用于显示
     */
    @Schema(title = "存储源名称", example = "阿里云 OSS 存储")
    private String name;

    /**
     * 存储源别名（key），用于 URL 中展示，格式如 http://ip:port/{存储源别名}
     */
    @Schema(title = "存储源别名", example = "存储源别名，用于 URL 中展示, 如 http://ip:port/{存储源别名}")
    private String key;

    /**
     * 存储源类型，如 FTP、S3、OneDrive 等
     */
    @Schema(title = "存储源类型")
    private StorageTypeEnum type;

    /**
     * 是否开启文件搜索功能
     */
    @Schema(title = "是否开启搜索", example = "true")
    private Boolean searchEnable;

    /**
     * 是否默认开启图片模式（缩略图浏览模式）
     */
    @Schema(title = "是否默认开启图片模式", example = "true")
    private Boolean defaultSwitchToImgMode;

}
