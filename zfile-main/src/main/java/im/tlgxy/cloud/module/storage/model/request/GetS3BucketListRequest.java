package im.tlgxy.cloud.module.storage.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 获取 S3 存储桶列表请求类
 * <p>
 * 用于请求获取兼容 S3 协议的对象存储服务中的存储桶（Bucket）列表。
 * 需要提供访问密钥（accessKey）、秘密密钥（secretKey）和端点地址（endPoint）进行身份验证。
 * 适用于 AWS S3、阿里云 OSS、腾讯云 COS 等兼容 S3 协议的对象存储服务。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="S3 bucket 列表请求类")
public class GetS3BucketListRequest {

	/**
	 * S3 访问密钥 ID（Access Key），用于身份认证
	 */
	@NotBlank(message = "accessKey 不能为空")
	@Schema(title = "accessKey", requiredMode = Schema.RequiredMode.REQUIRED, example = "XQEWQJI129JAS12")
	private String accessKey;

	/**
	 * S3 秘密访问密钥（Secret Key），用于身份认证
	 */
	@NotBlank(message = "secretKey 不能为空")
	@Schema(title = "secretKey", requiredMode = Schema.RequiredMode.REQUIRED, example = "EWQJI129JAS11AE2")
	private String secretKey;

	/**
	 * S3 服务端点地址（Endpoint），即对象存储服务的接入点域名
	 */
	@NotBlank(message = "EndPoint 不能为空")
	@Schema(title = "Endpoint 接入点", requiredMode = Schema.RequiredMode.REQUIRED, example = "oss-cn-beijing.aliyuncs.com")
	private String endPoint;

	/**
	 * S3 服务所在的区域（Region），如 cn-beijing 表示北京区域
	 */
	@Schema(title = "Endpoint 接入点", requiredMode = Schema.RequiredMode.REQUIRED, example = "cn-beijing")
	private String region;

}
