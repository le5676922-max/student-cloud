package im.tlgxy.cloud.module.storage.chain.command;

import im.tlgxy.cloud.core.exception.biz.StorageSourceFileForbiddenAccessBizException;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 目录访问权限校验命令（责任链 Command）。
 *
 * 该命令是文件处理责任链的第一个处理节点，负责检查用户请求的目录是否在禁止访问列表中。
 * 如果目录被配置为不可访问，则直接抛出 {@link StorageSourceFileForbiddenAccessBizException} 异常，
 * 阻止后续命令的执行。
 *
 * 通过 {@link FilterConfigService#checkFileIsInaccessible(Integer, String)} 方法判断目录是否可访问，
 * 该判断基于管理员配置的文件过滤规则。
 *
 * @author zhaojun
 */
@Service
public class FileAccessPermissionVerifyCommand implements Command {

	/**
	 * 过滤配置服务，用于检查文件/目录是否在禁止访问列表中。
	 */
	@Resource
	private FilterConfigService filterConfigService;

	/**
	 * 校验当前请求的目录是否有访问权限。
	 *
	 * 从上下文中提取存储源 ID 和请求路径，调用过滤服务进行判断。
	 * 如果目录被标记为不可访问，则抛出异常中断责任链。
	 *
	 * @param   context
	 *          文件处理责任链上下文，包含存储源信息和请求参数
	 *
	 * @return  false 表示责任链继续执行（校验通过时）；
	 *          校验失败时会抛出异常，不会返回 true
	 *
	 * @throws  StorageSourceFileForbiddenAccessBizException
	 *          当请求的目录被配置为禁止访问时抛出
	 */
	@Override
	public boolean execute(Context context) throws Exception {
		FileContext fileContext = (FileContext) context;
		Integer storageId = fileContext.getStorageId();
		FileListRequest fileListRequest = fileContext.getFileListRequest();

		// 检查文件目录是否是不可访问的, 如果是则抛出异常
		boolean isInaccessible = filterConfigService.checkFileIsInaccessible(storageId, fileListRequest.getPath());

		if (isInaccessible) {
			throw new StorageSourceFileForbiddenAccessBizException(storageId, fileListRequest.getPath());
		}

		return false;
	}

}
