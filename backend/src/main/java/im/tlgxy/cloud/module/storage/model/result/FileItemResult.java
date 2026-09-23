package im.tlgxy.cloud.module.storage.model.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文件/文件夹信息结果类
 * <p>
 * 封装单个文件或文件夹的基本信息，包括名称、修改时间、大小、
 * 类型（文件或文件夹）、路径和下载地址。
 * 实现了 Serializable 接口以支持序列化缓存。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="文件列表信息结果类")
public class FileItemResult implements Serializable {

    /**
     * 文件名（或文件夹名）
     */
    @Schema(title = "文件名", example = "a.mp4")
    private String name;

    /**
     * 文件/文件夹的最后修改时间
     */
    @Schema(title = "时间", example = "2020-01-01 15:22")
    private Date time;

    /**
     * 文件大小（字节），文件夹该值为 null 或 0
     */
    @Schema(title = "大小", example = "1024")
    private Long size;

    /**
     * 类型：FILE（文件）或 FOLDER（文件夹）
     */
    @Schema(title = "类型", example = "file")
    private FileTypeEnum type;

    /**
     * 文件/文件夹所在路径
     */
    @Schema(title = "所在路径", example = "/home/")
    private String path;

    /**
     * 文件下载地址（直链），文件夹该值为 null
     */
    @Schema(title = "下载地址", example = "http://www.example.com/a.mp4")
    private String url;

    /**
     * 获取完整路径（路径 + 名称的组合）
     * <p>
     * 将文件所在路径和文件名拼接，并自动移除重复的路径分隔符 /。
     * 使用 @JsonIgnore 注解表示该字段不参与 JSON 序列化。
     * </p>
     *
     * @return  路径和名称的组合，如 "/home/a.mp4"
     */
    @JsonIgnore
    public String getFullPath() {
        return StringUtils.concat(path, name);
    }

}
