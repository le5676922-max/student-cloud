package im.tlgxy.cloud.module.storage.chain.command;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 文件夹密码校验命令（责任链 Command）。
 *
 * 该命令负责校验当前请求的文件夹是否设置了访问密码，如果需要密码则进行密码验证。
 * 密码不正确时抛出 {@link BizException} 异常，中断文件列表查询流程。
 *
 * 特殊逻辑：
 * 在分享模式下，如果分享者拥有"忽略密码"权限（{@link FileOperatorTypeEnum#IGNORE_PASSWORD}），
 * 则跳过密码校验，允许被分享者无需密码即可访问。
 *
 * 密码校验通过后，会将匹配到的密码路径表达式（pattern）设置到上下文中，
 * 供前端判断当前目录下哪些子目录也受同一密码保护。
 *
 * @author zhaojun
 */
@Service
public class FolderPasswordVerifyCommand implements Command {

	/**
	 * 密码配置服务，用于校验访问密码和获取密码匹配的路径表达式。
	 */
	@Resource
	private PasswordConfigService passwordConfigService;

	/**
	 * 用户-存储源关联服务，用于检查分享者是否具有忽略密码的权限。
	 */
    @Resource
    private UserStorageSourceService userStorageSourceService;

	/**
	 * 校验当前请求文件夹的访问密码。
	 *
	 * 执行流程：
	 * 1. 从上下文提取存储源 ID、请求路径和密码；
	 * 2. 拼接完整路径（用户基础路径 + 请求路径）；
	 * 3. 检查分享者是否拥有忽略密码权限，如果有则跳过校验；
	 * 4. 调用密码服务进行密码验证；
	 * 5. 验证通过则将匹配的路径表达式写入上下文。
	 *
	 * @param   context
	 *          文件处理责任链上下文，包含请求参数和存储源信息
	 *
	 * @return  false 表示责任链继续执行（校验通过时）；
	 *          校验失败时会抛出异常，不会继续执行
	 *
	 * @throws  BizException
	 *          当密码校验不通过时抛出，异常中携带具体的错误码
	 */
	@Override
	public boolean execute(Context context) throws Exception {
		FileContext fileContext = (FileContext) context;
		Integer storageId = fileContext.getStorageId();

		FileListRequest fileListRequest = fileContext.getFileListRequest();
		String path = fileListRequest.getPath();
		String password = fileListRequest.getPassword();

		AbstractBaseFileService<?> fileService = fileContext.getFileService();
		// 拼接完整路径：用户基础路径 + 请求路径
		String fullPath = StringUtils.concat(fileService.getCurrentUserBasePath(), path);

        // 分享模式下，如果分享者拥有忽略密码权限，则跳过目录密码校验
        Integer operatorUserId = fileContext.getOperatorUserId();
        if (operatorUserId != null) {
            boolean ignorePwd = userStorageSourceService.hasUserStorageOperatorPermission(operatorUserId, storageId, FileOperatorTypeEnum.IGNORE_PASSWORD);
            if (ignorePwd) {
                return false;
            }
        }

		// 校验密码, 如果校验不通过, 则抛出业务异常并返回错误消息
		VerifyResultDTO verifyResultDTO = passwordConfigService.verifyPassword(storageId, fullPath, password);
		if (!verifyResultDTO.isPassed()) {
			throw new BizException(verifyResultDTO.getErrorCode());
		}

		// 设置当前文件夹所匹配的密码路径表达式，供前端使用
		fileContext.setPasswordPattern(verifyResultDTO.getPattern());;
		return false;
	}

}
