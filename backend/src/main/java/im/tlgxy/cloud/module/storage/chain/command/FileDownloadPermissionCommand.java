package im.tlgxy.cloud.module.storage.chain.command;

import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 文件下载/预览权限校验命令（责任链 Command）。
 *
 * 该命令是文件处理责任链的最后一个处理节点，负责校验当前用户是否有下载或预览文件的权限。
 * 如果用户既没有下载权限也没有预览权限，则会将文件列表中所有文件的下载地址（url 字段）置为 null，
 * 使得前端无法获取到文件的直链地址，从而限制用户的下载和预览行为。
 *
 * 注意：此命令不会因权限不足而抛出异常，而是通过清除 URL 的方式静默限制。
 *
 * @author zhaojun
 */
@Service
public class FileDownloadPermissionCommand implements Command {

    /**
     * 用户-存储源关联服务，用于查询用户对特定存储源的操作权限。
     */
    @Resource
    private UserStorageSourceService userStorageSourceService;

    /**
     * 校验下载和预览权限，权限不足时清除文件列表中的下载地址。
     *
     * 逻辑说明：
     * 1. 从上下文中提取存储源 ID 和操作者用户 ID；
     * 2. 分别检查用户是否具有 DOWNLOAD（下载）和 PREVIEW（预览）权限；
     * 3. 如果两个权限都不具备，则遍历文件列表，将每个文件的 url 字段设置为 null。
     *
     * @param   context
     *          文件处理责任链上下文，包含存储源信息和文件列表
     *
     * @return  始终返回 false，表示责任链继续执行（此命令不中断链的执行）
     */
    @Override
    public boolean execute(Context context) throws Exception {
        FileContext fileContext = (FileContext) context;
        Integer storageId = fileContext.getStorageId();
        Integer operatorUserId = fileContext.getOperatorUserId();

        // 检查用户是否具有下载和预览权限
        boolean hasDownloadPermission = userStorageSourceService.hasUserStorageOperatorPermission(operatorUserId, storageId, FileOperatorTypeEnum.DOWNLOAD);
        boolean hasPreviewPermission = userStorageSourceService.hasUserStorageOperatorPermission(operatorUserId, storageId, FileOperatorTypeEnum.PREVIEW);

        // 如果至少有一个权限，则不限制
        if (hasDownloadPermission || hasPreviewPermission) {
            return false;
        }
        // 两个权限都没有时，清除所有文件的下载地址
        fileContext.getFileItemList().forEach(file -> { file.setUrl(null);});
        return false;
    }

}
