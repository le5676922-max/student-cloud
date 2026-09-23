package im.tlgxy.cloud.module.storage.chain;

import im.tlgxy.cloud.module.storage.chain.command.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.chain.impl.ChainBase;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 文件处理责任链。
 *
 * 基于 Apache Commons Chain 框架实现的文件处理责任链，用于在文件列表查询过程中
 * 依次执行一系列处理命令（Command），每个命令负责一个独立的处理逻辑。
 *
 * 责任链中的命令按添加顺序依次执行，每个命令可以决定是否继续向下传递（返回 false 继续，返回 true 停止）。
 * 当前链包含以下命令（按执行顺序）：
 * 1. {@link FileAccessPermissionVerifyCommand} —— 校验目录访问权限
 * 2. {@link FolderPasswordVerifyCommand} —— 校验文件夹密码
 * 3. {@link FileHiddenCommand} —— 过滤隐藏文件
 * 4. {@link FileSortCommand} —— 文件排序
 * 5. {@link FileDownloadPermissionCommand} —— 校验下载/预览权限
 *
 * 这种责任链设计使得文件处理流程的各个步骤解耦，方便后续新增或调整处理步骤。
 *
 * @author zhaojun
 */
@Service
@Slf4j
public class FileChain extends ChainBase {

	/**
	 * 目录访问权限校验命令。
	 */
	@Resource
	private FileAccessPermissionVerifyCommand fileAccessPermissionVerifyCommand;

	/**
	 * 文件夹密码校验命令。
	 */
	@Resource
	private FolderPasswordVerifyCommand folderPasswordVerifyCommand;

	/**
	 * 文件隐藏过滤命令。
	 */
	@Resource
	private FileHiddenCommand fileHiddenCommand;

	/**
	 * 文件排序命令。
	 */
	@Resource
	private FileSortCommand fileSortCommand;

	/**
	 * 文件下载权限校验命令。
	 */
	@Resource
	private FileDownloadPermissionCommand fileDownloadPermissionCommand;

	/**
	 * 初始化责任链，将所有 Command 按顺序添加到链中。
	 *
	 * 该方法在 Spring Bean 初始化完成后自动调用（通过 @PostConstruct 注解），
	 * 确保在首次使用前责任链已经配置完毕。
	 * 命令的添加顺序决定了执行顺序。
	 */
	@PostConstruct
	public void init() {
		this.addCommand(fileAccessPermissionVerifyCommand);
		this.addCommand(folderPasswordVerifyCommand);
		this.addCommand(fileHiddenCommand);
		this.addCommand(fileSortCommand);
		this.addCommand(fileDownloadPermissionCommand);
	}

	/**
	 * 执行文件处理责任链。
	 *
	 * 将 {@link FileContext} 作为上下文传入父类的 {@link ChainBase#execute(org.apache.commons.chain.Context)} 方法，
	 * 责任链会按初始化时添加的顺序依次执行每个 Command。
	 *
	 * @param   content
	 *          文件处理上下文，包含存储源信息、请求参数、文件列表等数据
	 *
	 * @return  处理后的文件上下文（文件列表可能已被过滤、排序等操作修改）
	 *
	 * @throws  Exception
	 *          责任链中任一 Command 执行时抛出的异常
	 */
	public FileContext execute(FileContext content) throws Exception {
		super.execute(content);
		return content;
	}

}
