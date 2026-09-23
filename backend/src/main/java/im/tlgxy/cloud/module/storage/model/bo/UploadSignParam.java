package im.tlgxy.cloud.module.storage.model.bo;

import lombok.Data;

/**
 * 又拍云上传签名参数业务对象（BO）
 * <p>
 * 在请求又拍云上传签名时，需要向前端/又拍云 API 提供待上传文件的基本信息。
 * 此对象封装了这些信息，包括上传目标路径、文件大小和文件名。
 * 服务端根据这些参数计算上传签名（signature）和上传策略（policy），
 * 然后返回 {@link AuthModel} 对象供前端执行实际的上传操作。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class UploadSignParam {

	/**
	 * 文件上传到又拍云的目标路径
	 * 格式通常为：/bucket-name/path/to/file
	 */
	private String path;

	/**
	 * 待上传文件的大小（字节数）
	 * 用于又拍云服务端进行大小限制校验
	 */
	private Long size;

	/**
	 * 待上传文件的文件名
	 * 用于生成最终的文件路径和记录
	 */
	private String name;

}