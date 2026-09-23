package im.tlgxy.cloud.module.storage.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * S3 存储桶名称结果类
 * <p>
 * 封装兼容 S3 协议的对象存储服务中，单个存储桶（Bucket）的基本信息，
 * 包括存储桶的名称和创建时间。
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
@Schema(title="S3 bucket 名称结果类")
public class S3BucketNameResult {

	/**
	 * 存储桶（Bucket）名称
	 */
	@Schema(title = "bucket 名称", example = "student-cloud")
	private String name;

	/**
	 * 存储桶（Bucket）创建时间
	 */
	@Schema(title = "bucket 创建时间", example = "2022-01-01 15:22")
	private Date date;

}
