package im.tlgxy.cloud.module.storage.model.dto;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 存储源初始化数据传输对象（DTO）
 * <p>
 * 用于系统启动时初始化存储源的配置信息。包含存储源的基本信息和完整的配置参数列表。
 * 与常规的存储源 DTO 不同，此对象携带的是 {@link StorageSourceConfig} 原始配置列表，
 * 而非解析后的 {@link StorageSourceAllParamDTO}，因为在初始化阶段配置尚未被解析。
 * </p>
 * <p>
 * 提供静态工厂方法 {@link #convert(StorageSource, List)} 用于从实体类快速构建 DTO 对象。
 * </p>
 */
@Data
public class StorageSourceInitDTO {

    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    @Schema(title = "存储源名称", example = "阿里云 OSS 存储")
    private String name;

    @Schema(title = "存储源别名", example = "存储源别名，用于 URL 中展示, 如 http://ip:port/{存储源别名}")
    private String key;

    @Schema(title = "存储源类型", example = "ftp")
    private StorageTypeEnum type;

    @Schema(title = "存储源参数")
    List<StorageSourceConfig> storageSourceConfigList;

    /**
     * 从存储源实体和配置列表构建初始化 DTO
     * <p>
     * 将 {@link StorageSource} 实体和对应的 {@link StorageSourceConfig} 列表
     * 合并转换为一个完整的初始化传输对象。
     * </p>
     *
     * @param storageSource             存储源实体（包含基本信息）
     * @param storageSourceConfigList   存储源配置项列表（包含扩展参数）
     * @return 组装好的存储源初始化 DTO
     */
    public static StorageSourceInitDTO convert(StorageSource storageSource, List<StorageSourceConfig> storageSourceConfigList) {
        StorageSourceInitDTO storageSourceInitDTO = new StorageSourceInitDTO();
        storageSourceInitDTO.setId(storageSource.getId());
        storageSourceInitDTO.setType(storageSource.getType());
        storageSourceInitDTO.setName(storageSource.getName());
        storageSourceInitDTO.setKey(storageSource.getKey());
        storageSourceInitDTO.setStorageSourceConfigList(storageSourceConfigList);
        return storageSourceInitDTO;
    }

}
