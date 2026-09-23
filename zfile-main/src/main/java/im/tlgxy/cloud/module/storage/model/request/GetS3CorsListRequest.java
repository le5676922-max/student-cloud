package im.tlgxy.cloud.module.storage.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取 S3 存储桶 CORS 配置列表请求类
 * <p>
 * 用于请求获取兼容 S3 协议的对象存储服务中，指定存储桶（Bucket）的跨域资源共享（CORS）配置。
 * 继承自获取存储桶列表的请求参数，额外需要指定目标存储桶名称。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="S3 bucket 列表请求类")
public class GetS3CorsListRequest {

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
	 * S3 服务所在的区域（Region）
	 */
	@Schema(title = "Endpoint 接入点", requiredMode = Schema.RequiredMode.REQUIRED, example = "cn-beijing")
	private String region;

	/**
	 * 目标存储桶名称，用于查询该桶的 CORS 配置
	 */
	@Schema(title = "存储桶名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "student-cloud-bucket")
	private String bucketName;

}
