package im.tlgxy.cloud.module.storage.chain.command;

import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件隐藏过滤命令（责任链 Command）。
 *
 * 该命令负责根据管理员配置的隐藏规则，从文件列表中过滤掉需要隐藏的文件/文件夹。
 * 通过 {@link FilterConfigService#checkFileIsHidden(Integer, String)} 方法判断每个文件是否匹配隐藏规则，
 * 匹配到的文件将被从结果列表中移除。
 *
 * 使用 Java Stream API 进行过滤操作，只保留不匹配隐藏规则的文件。
 *
 * @author zhaojun
 */
@Service
public class FileHiddenCommand implements Command {

	/**
	 * 过滤配置服务，用于检查文件是否匹配隐藏规则。
	 */
	@Resource
	private FilterConfigService filterConfigService;

	/**
	 * 执纾文件隐藏过滤逻辑。
	 *
	 * 从上下文中获取文件列表，逐个检查每个文件是否匹配隐藏规则，
	 * 过滤掉需要隐藏的文件，更新上下文中的文件列表。
	 *
	 * @param   context
	 *          文件处理责任链上下文，包含文件列表数据
	 *
	 * @return  始终返回 false，表示责任链继续执行
	 */
	@Override
	public boolean execute(Context context) throws Exception {
		FileContext fileContext = (FileContext) context;
		Integer storageId = fileContext.getStorageId();

		List<FileItemResult> fileItemList = fileContext.getFileItemList();
		// 如果文件列表为空，无需过滤，直接返回
		if (CollectionUtils.isEmpty(fileItemList)) {
			return false;
		}

		// 使用 Stream 过滤：只保留不被隐藏规则匹配的文件
		List<FileItemResult> result = fileItemList.stream()
				.filter(fileItem -> !filterConfigService.checkFileIsHidden(storageId, fileItem.getFullPath()))
				.collect(Collectors.toList());

		// 更新上下文中的文件列表为过滤后的结果
		fileContext.setFileItemList(result);
		return false;
	}

}
