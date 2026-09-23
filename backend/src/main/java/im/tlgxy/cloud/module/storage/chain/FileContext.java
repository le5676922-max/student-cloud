package im.tlgxy.cloud.module.storage.chain;

import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.chain.impl.ContextBase;

import java.util.List;

/**
 * 文件处理责任链的上下文对象。
 *
 * 作为责任链各 Command 之间共享数据的载体，继承自 Apache Commons Chain 的 {@link ContextBase}。
 * 包含了文件列表查询和处理过程中需要的所有数据，如存储源信息、请求参数、文件列表结果等。
 *
 * 责任链中的每个 Command 都可以读取和修改此上下文中的数据，
 * 上一个 Command 的修改结果会传递给下一个 Command。
 *
 * @author zhaojun
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@Builder
public class FileContext extends ContextBase {

	/**
	 * 存储源 ID，唯一标识一个存储源配置。
	 */
	private Integer storageId;

	/**
	 * 文件列表请求参数对象，包含查询路径、排序方式、密码等请求信息。
	 */
	private FileListRequest fileListRequest;

	/**
	 * 根据请求参数获取到的原始文件列表。
	 * 在责任链的各个 Command 中可能会被过滤、排序等操作修改。
	 */
	private List<FileItemResult> fileItemList;

	/**
	 * 当前目录所匹配的密码路径表达式。
	 * 由 {@link FolderPasswordVerifyCommand} 在校验密码后设置，
	 * 用于前端判断哪些子目录也受同一密码保护。
	 */
	private String passwordPattern;

	/**
	 * 当前存储源对应的文件服务实例。
	 * 用于获取用户基础路径、执行文件操作等。
	 */
    private AbstractBaseFileService<?> fileService;

    /**
     * 操作者用户 ID。
     * 在直接访问模式下为当前登录用户的 ID；
     * 在分享模式下为分享者的用户 ID，用于判断分享者是否具有忽略密码等权限。
     */
    private Integer operatorUserId;

}
