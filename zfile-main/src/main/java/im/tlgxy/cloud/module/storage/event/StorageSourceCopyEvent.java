package im.tlgxy.cloud.module.storage.event;

import lombok.Data;

/**
 * 存储源复制事件
 * <p>
 * 当复制存储源时发布此事件。事件中携带源存储源 ID 和新创建的存储源 ID，
 * 以便其他模块（如 README 配置模块）监听并同步复制相关的配置数据。
 * Spring 事件监听机制确保复制操作的扩展性，无需修改核心代码即可添加新的复制行为。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class StorageSourceCopyEvent {

    /** 源存储源 ID，即被复制的存储源的唯一标识 */
    private Integer fromId;

    /** 新创建的存储源 ID，即复制后生成的存储源的唯一标识 */
    private Integer newId;

    /**
     * 构造存储源复制事件
     *
     * @param fromId  源存储源 ID
     * @param newId   新创建的存储源 ID
     */
    public StorageSourceCopyEvent(Integer fromId, Integer newId) {
        this.fromId = fromId;
        this.newId = newId;
    }

}