package im.tlgxy.cloud.module.log.model.result;

import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 下载日志结果类
 * <p>
 * 用于前端展示的下载日志数据传输对象。
 * 相比 DownloadLog 实体，额外包含了存储源名称、存储源类型、拼接好的短链地址和直链地址等展示字段。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class DownloadLogResult {

	/** 日志记录 ID */
	@Schema(title="")
	private Integer id;

	/** 下载文件路径 */
	@Schema(title="文件路径")
	private String path;

	/** 存储源类型枚举 */
	@Schema(title = "存储源类型")
	private StorageTypeEnum storageType;

	/** 存储源名称（如"我的本地存储"） */
	@Schema(title = "存储源名称", example = "我的本地存储")
	private String storageName;

	/** 存储源 Key */
	@Schema(title = "存储源Key", example = "local")
	private String storageKey;

	/** 下载发生时间 */
	@Schema(title="访问时间")
	private Date createTime;

	/** 访问者 IP */
	@Schema(title="访问 ip")
	private String ip;

	/** 短链 Key（如果是通过短链下载） */
	@Schema(title = "短链 Key")
	private String shortKey;

	/** 访问者 User-Agent */
	@Schema(title="访问 user_agent")
	private String userAgent;

	/** 访问来源 Referer */
	@Schema(title="访问 referer")
	private String referer;

	/** 拼接好的短链完整地址（由服务端拼接，方便前端直接使用） */
	@Schema(title="短链地址")
	private String shortLink;

	/** 拼接好的直链完整地址（由服务端拼接，方便前端直接使用） */
	@Schema(title="直链地址")
	private String pathLink;

}