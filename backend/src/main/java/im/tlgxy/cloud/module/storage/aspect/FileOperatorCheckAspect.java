package im.tlgxy.cloud.module.storage.aspect;

import im.tlgxy.cloud.core.exception.biz.StorageSourceIllegalOperationBizException;
import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.OnlyOfficeKeyCacheUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.link.model.request.BatchGenerateLinkRequest;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import im.tlgxy.cloud.module.storage.annotation.StoragePermissionCheck;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import im.tlgxy.cloud.module.user.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 文件操作权限校验切面。
 *
 * 该切面负责拦截所有文件操作相关的方法（如文件列表查询、上传、下载、删除、重命名、移动、复制等），
 * 在执行操作前校验当前用户是否具有对应的操作权限。
 *
 * 权限校验逻辑：
 * 1. 检查当前用户是否被启用；
 * 2. 检查用户是否被授权访问该存储源；
 * 3. 检查用户对该存储源是否具有执行当前操作类型的权限；
 * 4. 如果权限不足，抛出 {@link StorageSourceIllegalOperationBizException} 异常。
 *
 * 此外，在文件/文件夹发生变更操作时，还会清除相关的 OnlyOffice 缓存。
 *
 * @author zhaojun
 */
@Aspect
@Component
@Slf4j
public class FileOperatorCheckAspect {

	/**
	 * 存储源服务，用于根据存储源 key 查找存储源 ID 等操作。
	 */
	@Resource
	private StorageSourceService storageSourceService;

	/**
	 * 用户-存储源关联服务，用于查询用户对特定存储源的权限配置。
	 */
	@Resource
	private UserStorageSourceService userStorageSourceService;

	@Resource
	private UserService userService;

	/**
	 * 生成直链（外链）权限校验。
	 *
	 * 针对标注了 {@link StoragePermissionCheck} 注解的方法，提取其 action 参数，
	 * 如果操作类型为 LINK（生成直链），则进行专门的权限检查。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @param   storagePermissionCheck
	 *          方法上的 {@link StoragePermissionCheck} 注解实例
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("@annotation(storagePermissionCheck)")
	public Object annotationCheck(ProceedingJoinPoint point, StoragePermissionCheck storagePermissionCheck) throws Throwable {
		FileOperatorTypeEnum action = storagePermissionCheck.action();
		if (action == FileOperatorTypeEnum.LINK) {
			// 生成直链操作需要特殊处理：需要从参数中提取存储源 key
			return linkActionCheck(point);
		}

		// 其他操作类型直接放行（由各自的切面方法处理）
		return point.proceed();
	}

	/**
	 * 直链生成操作的权限校验。
	 *
	 * 从方法参数中提取存储源 key（支持单个 key 字符串或 {@link BatchGenerateLinkRequest} 批量请求对象），
	 * 然后调用通用权限校验方法进行判断。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	public Object linkActionCheck(ProceedingJoinPoint point) throws Throwable {
		FileOperatorTypeEnum action = FileOperatorTypeEnum.LINK;
		Object arg = point.getArgs()[0];
		// 兼容单个 storageKey 字符串和批量请求对象两种参数类型
		String storageKey = (arg instanceof BatchGenerateLinkRequest) ?((BatchGenerateLinkRequest) arg).getStorageKey() : (String) arg;
		Integer storageId = storageSourceService.findIdByKey(storageKey);

		boolean allowAccess = allowAccess(storageId, action);
		if (allowAccess) {
			return point.proceed();
		} else {
			throw new StorageSourceIllegalOperationBizException(storageId, action);
		}
	}

	/**
	 * 存储源可用性校验——fileList 和 getFileItem 方法的切面。
	 *
	 * 在执行文件列表查询和单个文件查询前，检查用户是否有权访问该存储源（AVAILABLE 权限）。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.fileList(..)) || " +
			"execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.getFileItem(..))")
	public Object availableAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.AVAILABLE);
		return point.proceed();
	}

	/**
	 * 新建文件/文件夹权限校验切面。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.newFolder(..))")
	public Object newFolderAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.NEW_FOLDER);
		return point.proceed();
	}

	/**
	 * 删除文件/文件夹权限校验切面。
	 *
	 * 在删除操作成功后，还会清除相关的 OnlyOffice 缓存，避免缓存数据不一致。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.delete*(..))")
	public Object deleteAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.DELETE);

		Object result = point.proceed();

		// 删除成功后清除 OnlyOffice 缓存
		boolean isFolder = point.getSignature().getName().equals("deleteFolder");
		AbstractBaseFileService<?> targetService = (AbstractBaseFileService<?>) point.getTarget();
		String path = (String) point.getArgs()[0];
		String name = (String) point.getArgs()[1];
		String currentUserBasePath = targetService.getCurrentUserBasePath();
		String fullPath = StringUtils.concat(currentUserBasePath, path, name);
		clearOnlyOfficeCache(fullPath, targetService.storageId, isFolder);

		return result;
	}

	/**
	 * 文件上传权限校验切面。
	 *
	 * 覆盖 getUploadUrl（获取上传地址）和 uploadFile（代理上传文件）两个方法。
	 * 在上传操作成功后，清除相关的 OnlyOffice 缓存。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.getUploadUrl(..)) || " +
			"execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService.uploadFile(..))")
	public Object uploadAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.UPLOAD);

		Object[] args = point.getArgs();
		AbstractBaseFileService<?> targetService = (AbstractBaseFileService<?>) point.getTarget();
		String currentUserBasePath = targetService.getCurrentUserBasePath();

		// 根据不同方法名拼接完整文件路径
		String fullPath;
		String methodName = point.getSignature().getName();
		if (Objects.equals(methodName, "getUploadUrl")) {
			fullPath = StringUtils.concat(currentUserBasePath, (String) args[0], (String) args[1]);
		} else if (Objects.equals(methodName, "uploadFile")) {
			fullPath = StringUtils.concat(currentUserBasePath, (String) args[0]);
		} else {
			throw new IllegalArgumentException("上传校验异常.");
		}

		Object result = point.proceed();
		// 上传成功后清除 OnlyOffice 缓存
		clearOnlyOfficeCache(fullPath, targetService.storageId, false);

		return result;
	}

	/**
	 * 重命名文件/文件夹权限校验切面。
	 *
	 * 在重命名操作成功后，清除源路径和目标路径相关的 OnlyOffice 缓存。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.rename*(..))")
	public Object renameAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.RENAME);

		AbstractBaseFileService<?> targetService = (AbstractBaseFileService<?>) point.getTarget();
		String currentUserBasePath = targetService.getCurrentUserBasePath();

		Object[] args = point.getArgs();
		String path = (String) args[0];
		String name = (String) args[1];
		String newName = (String) args[2];
		// 重命名前后的完整路径
		String sourceFullPath = StringUtils.concat(currentUserBasePath, path, name);
		String targetFullPath = StringUtils.concat(currentUserBasePath, path, newName);

		Object result = point.proceed();
		// 清除源路径的 OnlyOffice 缓存
		clearOnlyOfficeCache(sourceFullPath, targetService.storageId, Objects.equals(point.getSignature().getName(), "renameFolder"));

		return result;
	}

	/**
	 * 移动文件/文件夹权限校验切面。
	 *
	 * 在移动操作成功后，清除相关路径的 OnlyOffice 缓存。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.move*(..))")
	public Object moveAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.MOVE);
		Object result = point.proceed();

		// 移动成功后清除 OnlyOffice 缓存
		AbstractBaseFileService<?> targetService = (AbstractBaseFileService<?>) point.getTarget();
		String path = (String) point.getArgs()[0];
		String name = (String) point.getArgs()[1];
		String currentUserBasePath = targetService.getCurrentUserBasePath();
		String fullPath = StringUtils.concat(currentUserBasePath, path, name);
		clearOnlyOfficeCache(fullPath, targetService.storageId, Objects.equals(point.getSignature().getName(), "moveFolder"));

		return result;
	}

	/**
	 * 复制文件/文件夹权限校验切面。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @return  目标方法的返回值
	 *
	 * @throws  Throwable
	 *          权限校验失败或目标方法自身抛出的异常
	 */
	@Around("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.copy*(..))")
	public Object copyAround(ProceedingJoinPoint point) throws Throwable {
		checkPermission(point, FileOperatorTypeEnum.COPY);
		return point.proceed();
	}

	/**
	 * 通用权限校验方法。
	 *
	 * 从连接点的目标对象中提取存储源 ID，然后调用 {@link #allowAccess(Integer, FileOperatorTypeEnum)} 进行权限判断。
	 * 如果权限不足，则抛出 {@link StorageSourceIllegalOperationBizException} 异常。
	 *
	 * @param   point
	 *          AOP 连接点
	 *
	 * @param   fileOperatorType
	 *          要校验的文件操作类型
	 *
	 * @throws  StorageSourceIllegalOperationBizException
	 *          当用户没有对应操作权限时抛出
	 */
	private void checkPermission(ProceedingJoinPoint point, FileOperatorTypeEnum fileOperatorType) {
		// 获取对应的存储源 service，从中提取存储源 ID
		AbstractBaseFileService<?> targetService = (AbstractBaseFileService<?>) point.getTarget();
		Integer storageId = targetService.storageId;

		boolean allowAccess = allowAccess(storageId, fileOperatorType);

		if (BooleanUtils.isFalse(allowAccess)) {
			throw new StorageSourceIllegalOperationBizException(storageId, fileOperatorType);
		}
	}

	/**
	 * 判断当前用户对指定存储源是否拥有执行特定操作类型的权限。
	 *
	 * 校验步骤：
	 * 1. 检查当前用户是否被启用，未启用则拒绝所有操作；
	 * 2. 查询用户对该存储源的授权记录，未授权或已禁用则拒绝；
	 * 3. 如果是 AVAILABLE（可用性）检查，只要用户启用了该存储源即可通过；
	 * 4. 对于其他操作类型，检查用户的权限集合中是否包含该操作类型。
	 *
	 * @param   storageId
	 *          存储源 ID
	 *
	 * @param   fileOperatorType
	 *          要校验的文件操作类型
	 *
	 * @return  true 表示允许访问，false 表示拒绝访问
	 */
	private boolean allowAccess(Integer storageId, FileOperatorTypeEnum fileOperatorType) {
		// 获取当前登录用户，检查用户是否被启用
		User currentUser = TlgxyCloudAuthUtil.getCurrentUser();
		if (currentUser == null || BooleanUtils.isNotTrue(currentUser.getEnable())) {
			return false;
		}
		userService.requireActiveUser(currentUser.getId());

		// 查询用户对该存储源的授权配置
		UserStorageSource userStorageSource = userStorageSourceService.getByUserIdAndStorageId(TlgxyCloudAuthUtil.getCurrentUserId(), storageId);

		// 如果未授权该存储源，或授权已被禁用，则默认禁止所有类型的操作
		if (userStorageSource == null) {
			return false;
		}
		Boolean enable = userStorageSource.getEnable();
		if (enable == null || !enable) {
			return false;
		}

		// AVAILABLE 类型只要存储源可用即可通过
		if (fileOperatorType == FileOperatorTypeEnum.AVAILABLE) {
			return true;
		}

		// 检查用户权限集合中是否包含当前操作类型
		Set<String> permissions = userStorageSource.getPermissions();
		return permissions != null && permissions.contains(fileOperatorType.getValue());
	}

	/**
	 * 清除 OnlyOffice 在线编辑的缓存数据。
	 *
	 * 当文件或文件夹发生删除、重命名、移动、上传等操作时，需要清除 OnlyOffice 相关的缓存，
	 * 以确保在线编辑功能不会引用到已失效的文件路径。
	 *
	 * 如果是文件夹操作，会清除该文件夹下所有文件的 OnlyOffice 缓存；
	 * 如果是文件操作，只清除该单个文件的缓存。
	 *
	 * @param 	fullPath
	 * 			文件或文件夹的完整路径（包含用户基础路径）
	 *
	 * @param 	storageId
	 * 			存储源 ID
	 *
	 * @param 	isFolder
	 * 			是否是对文件夹的操作，true 表示文件夹操作，false 表示文件操作
	 */
	private void clearOnlyOfficeCache(String fullPath, Integer storageId, boolean isFolder) {
		try {
			String storageKey = storageSourceService.findStorageKeyById(storageId);
			if (isFolder) {
				// 删除/重命名文件夹时，清除该文件夹下所有文件的 OnlyOffice 缓存
				List<OnlyOfficeFile> caches = OnlyOfficeKeyCacheUtils.removeByFolder(new OnlyOfficeFile(storageKey, fullPath));
				if (CollectionUtils.isNotEmpty(caches)) {
					log.debug("删除/重命名文件夹时, 清除 OnlyOffice 缓存 {} 个", caches);
				}
			} else {
				// 删除/重命名单个文件时，清除该文件的 OnlyOffice 缓存
				OnlyOfficeFile onlyOfficeFile = OnlyOfficeKeyCacheUtils.removeByFile(new OnlyOfficeFile(storageKey, fullPath));
				if (onlyOfficeFile != null) {
					log.debug("删除/重命名文件时, 清除 OnlyOffice 缓存: {}", onlyOfficeFile);
				}
			}
		} catch (Exception e) {
			// 缓存清除失败不影响主流程，仅记录错误日志
			log.error("清除 OnlyOffice 缓存失败", e);
		}
	}

}
