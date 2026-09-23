package im.tlgxy.cloud.module.share.model.result;

import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 分享文件信息响应结果类
 * <p>
 * 当访问者浏览某个分享链接中的文件时，后端返回此对象，包含当前目录下的文件列表、
 * 路径信息（当前路径和父路径）、分享链接元信息、是否为根目录标记以及分享创建者的权限映射。
 * </p>
 * <p>
 * 提供两个构造方法：
 * <ul>
 *   <li>无参构造：供反序列化框架使用</li>
 *   <li>带参构造：由服务层直接构建，自动计算 isRoot 和 parentPath</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "分享文件信息响应")
public class ShareFileInfoResult {

    /**
     * 当前目录下的文件/文件夹列表
     * <p>
     * 如果创建分享时指定了分享条目（shareEntries），则根目录只显示被选中的条目，
     * 子目录则显示全部内容。
     * </p>
     */
    @Schema(title = "文件列表")
    private List<FileItemResult> fileItemList;

    /**
     * 当前正在浏览的相对路径
     * <p>
     * 相对于分享根目录的路径。根目录时为 "/" 或空字符串。
     * </p>
     */
    @Schema(title = "当前路径")
    private String currentPath;

    /**
     * 当前路径的父级路径
     * <p>
     * 用于前端实现"返回上一级"的导航功能。
     * 当 currentPath 为根目录时，parentPath 为 null。
     * </p>
     */
    @Schema(title = "父路径")
    private String parentPath;

    /**
     * 分享链接的详细信息
     * <p>
     * 包含分享 key、过期状态、存储源信息、分享类型等元数据。
     * </p>
     */
    @Schema(title = "分享信息")
    private ShareLinkResult shareLinkInfo;

    /**
     * 当前路径是否为分享的根目录
     * <p>
     * 当 currentPath 为 "/" 时为 true，否则为 false。
     * 前端可以据此决定是否显示"返回上一级"按钮。
     * </p>
     */
    @Schema(title = "是否为根目录")
    private Boolean isRoot;

    /**
     * 分享创建者在该存储源上的权限映射
     * <p>
     * Key 为权限名称（如 upload、delete、rename 等），Value 为布尔值表示是否拥有该权限。
     * 访问者可以据此了解分享者在该存储源上拥有的操作权限范围。
     * </p>
     */
    @Schema(title = "分享者在该存储源的权限映射")
    private Map<String, Boolean> permission;

    /**
     * 无参构造方法
     * <p>
     * 供 JSON 反序列化、MyBatis 等框架使用。
     * </p>
     */
    public ShareFileInfoResult() {
    }

    /**
     * 带参构造方法
     * <p>
     * 根据文件列表、当前路径和分享链接信息构建响应对象。
     * 会自动计算 isRoot（通过判断 currentPath 是否为 "/"）和 parentPath
     * （通过截取 currentPath 中最后一个 "/" 之前的部分得出）。
     * </p>
     *
     * @param fileItemList   当前目录下的文件/文件夹列表
     * @param currentPath    当前相对路径
     * @param shareLinkInfo  分享链接详细信息
     */
    public ShareFileInfoResult(List<FileItemResult> fileItemList, String currentPath, ShareLinkResult shareLinkInfo) {
        this.fileItemList = fileItemList;
        this.currentPath = currentPath;
        this.shareLinkInfo = shareLinkInfo;
        // 判断是否为根目录：当前路径为 "/" 时即为根目录
        this.isRoot = "/".equals(currentPath);
        // 非根目录时计算父级路径
        if (!isRoot && currentPath != null) {
            // 找到最后一个 "/" 的位置
            int lastSlashIndex = currentPath.lastIndexOf('/');
            if (lastSlashIndex > 0) {
                // 截取父路径，例如 "/a/b" -> "/a"
                this.parentPath = currentPath.substring(0, lastSlashIndex);
            } else {
                // 如果只有一个 "/"，说明父路径就是根目录
                this.parentPath = "/";
            }
        }
    }
}
