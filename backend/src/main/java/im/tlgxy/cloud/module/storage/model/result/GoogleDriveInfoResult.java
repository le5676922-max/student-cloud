package im.tlgxy.cloud.module.storage.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Google Drive 驱动器基本信息结果类
 * <p>
 * 封装 Google Drive 中单个驱动器（Drive）的基本信息，
 * 包括驱动器的唯一标识 ID 和显示名称。
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
@Schema(title="gd drive 基本信息结果类")
public class GoogleDriveInfoResult {

	/**
	 * Google Drive 驱动器 ID，唯一标识一个驱动器
	 */
	@Schema(title = "drive id", example = "0AGrY0xF1D7PEUk9PVB")
	private String id;

	/**
	 * Google Drive 驱动器名称
	 */
	@Schema(title = "drive 名称", example = "student-cloud")
	private String name;

}
