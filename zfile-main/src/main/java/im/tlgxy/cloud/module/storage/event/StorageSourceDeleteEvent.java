package im.tlgxy.cloud.module.storage.event;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import lombok.Data;

/**
 * 存储源删除事件
 * <p>
 * 当删除存储源时发布此事件。事件中携带被删除存储源的核心字段信息
 * （ID、Key、名称、类型），其他模块可以监听此事件并执行相应的清理操作，
 * 比如删除与此存储源关联的 README 配置、缓存数据等。
 * </p>
 * <p>
 * 注意：为了在存储源数据从数据库中删除后仍能访问这些信息，事件在删除前发布，
 * 并将需要的关键信息保存在事件对象中。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class StorageSourceDeleteEvent {

    /** 被删除的存储源的唯一标识 */
    private Integer id;

    /** 被删除的存储源的业务键（用于 URL 访问路径） */
    private String key;

    /** 被删除的存储源的显示名称 */
    private String name;

    /** 被删除的存储源的存储类型（如本地存储、阿里云 OSS 等） */
    private StorageTypeEnum type;

    /**
     * 构造存储源删除事件
     * <p>
     * 从存储源实体中提取关键字段，构造事件对象。
     * 这样在存储源被从数据库删除后，事件中仍然保留了必要的信息供监听器使用。
     * </p>
     *
     * @param storageSource  被删除的存储源实体对象
     */
    public StorageSourceDeleteEvent(StorageSource storageSource) {
        this.id = storageSource.getId();
        this.key = storageSource.getKey();
        this.name = storageSource.getName();
        this.type = storageSource.getType();
    }

}