package im.tlgxy.cloud.module.storage.chain.command;

import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.core.util.FileComparator;
import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件排序命令（责任链 Command）。
 *
 * 该命令负责根据用户请求中的排序参数（排序字段和排序方向），对文件列表进行排序。
 * 使用自定义的 {@link FileComparator} 比较器实现自然排序（支持按名称、大小、修改时间等字段排序）。
 *
 * 排序逻辑：
 * 1. 从请求参数中提取 orderBy（排序字段）和 orderDirection（排序方向）；
 * 2. 如果排序参数为空，则跳过排序（保持原始顺序）；
 * 3. 创建文件列表副本进行排序，避免对原始数据产生影响。
 *
 * @author zhaojun
 */
@Service
public class FileSortCommand implements Command {

	/**
	 * 按照请求的排序字段和排序方向对文件列表进行排序。
	 *
	 * 如果请求中没有指定排序参数，则不执行任何排序操作。
	 * 排序时创建列表副本进行操作，避免影响上一步 Command 的数据。
	 *
	 * @param   context
	 *          文件处理责任链上下文，包含文件列表和排序参数
	 *
	 * @return  始终返回 false，表示责任链继续执行
	 */
	@Override
	public boolean execute(Context context) throws Exception {
		FileContext fileContext = (FileContext) context;

		List<FileItemResult> fileItemList = fileContext.getFileItemList();
		FileListRequest fileListRequest = fileContext.getFileListRequest();

		// 如果没有指定排序字段或排序方向，则不排序，直接返回
		if (fileListRequest.getOrderBy() == null || fileListRequest.getOrderDirection() == null) {
			return false;
		}

		// 创建副本, 防止排序对原数据产生影响（原数据可能被其他命令或调用方使用）
		List<FileItemResult> copyList = new ArrayList<>(fileItemList);

		// 使用 FileComparator 按照自然排序规则进行排序
		copyList.sort(new FileComparator(fileListRequest.getOrderBy(), fileListRequest.getOrderDirection()));
		fileContext.setFileItemList(copyList);
		return false;
	}

}
