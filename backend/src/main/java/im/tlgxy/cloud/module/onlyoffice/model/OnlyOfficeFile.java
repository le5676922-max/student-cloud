package im.tlgxy.cloud.module.onlyoffice.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * OnlyOffice 缓存文件信息实体
 *
 * <p>用于在 OnlyOffice 预览和回调之间传递文件上下文信息。
 * 缓存的相等性仅以 {@code storageKey + pathAndName} 为准, 保证 {@link im.tlgxy.cloud.core.util.OnlyOfficeKeyCacheUtils}
 * 通过文件路径就能命中之前发放预览时存储的上下文(包含发起预览的用户 ID 与编辑权限)。
 * 任意两个 key 相同且完整路径相同的缓存对象被视为相等，从而实现同一文件的多人协同编辑。</p>
 *
 * @author zhaojun
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(of = {"storageKey", "pathAndName"})
public class OnlyOfficeFile {

    /** 存储源 key，标识文件所属的存储源 */
    private String storageKey;

    /**
     * 完整路径(已拼接用户根目录 / 分享根目录), 作为缓存键, 保证不同用户根目录下同名文件互不冲突,
     * 同时让同一物理文件的多人预览能命中同一个 OnlyOffice 协同编辑 key.
     */
    private String pathAndName;

    /**
     * 用户视角的原始相对路径(不含用户根目录). 回调写回时使用该路径调用 {@code uploadFile},
     * 让下游存储实现内部再拼接 {@code getCurrentUserBasePath()}, 避免重复拼接导致文件落到错误位置.
     */
    private String originalPath;

    /**
     * 发起预览的用户 ID. 用于 OnlyOffice 回调时的权限再校验与上下文切换,
     * 防止回调请求伪造 {@code users} 字段越权写入.
     */
    private Integer userId;

    /**
     * 生成预览配置时是否允许编辑保存. 回调写入前会再次校验此字段, 拒绝当时不允许编辑的请求.
     */
    private boolean allowEdit;

    /**
     * 仅用于通过 {@code storageKey + pathAndName} 查询/失效缓存的轻量构造器.
     *
     * @param storageKey 存储源 key
     * @param pathAndName 完整文件路径
     */
    public OnlyOfficeFile(String storageKey, String pathAndName) {
        this.storageKey = storageKey;
        this.pathAndName = pathAndName;
    }

    /**
     * 全参数构造器，用于创建包含完整上下文信息的缓存对象
     *
     * @param storageKey  存储源 key
     * @param pathAndName 完整文件路径（含用户根目录）
     * @param originalPath 用户视角的原始相对路径
     * @param userId      发起预览的用户 ID
     * @param allowEdit   是否允许编辑
     */
    public OnlyOfficeFile(String storageKey, String pathAndName, String originalPath, Integer userId, boolean allowEdit) {
        this.storageKey = storageKey;
        this.pathAndName = pathAndName;
        this.originalPath = originalPath;
        this.userId = userId;
        this.allowEdit = allowEdit;
    }

}