package im.tlgxy.cloud.module.storage.controller.helper;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.dto.TlgxyCloudCORSRule;
import im.tlgxy.cloud.module.storage.model.request.GetS3BucketListRequest;
import im.tlgxy.cloud.module.storage.model.request.GetS3CorsListRequest;
import im.tlgxy.cloud.module.storage.model.result.S3BucketNameResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.GetBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S3 工具辅助
 *
 * @author zhaojun
 */
@Tag(name = "S3 工具辅助模块")
@Controller
@RequestMapping("/s3")
@RequiredArgsConstructor
public class S3HelperController {

	private final S3HelperEndpointPolicy endpointPolicy;
	private final S3HelperClientFactory clientFactory;

	@PostMapping("/getBuckets")
	@ResponseBody
	@ApiOperationSupport(order = 1)
	@Operation(summary = "获取 S3 存储器列表")
	public AjaxJson<List<S3BucketNameResult>> getBucketNames(@Valid @RequestBody GetS3BucketListRequest getS3BucketListRequest) {
		List<S3BucketNameResult> bucketNameList = new ArrayList<>();
		String accessKey = getS3BucketListRequest.getAccessKey();
		String secretKey = getS3BucketListRequest.getSecretKey();
		var endpoint = endpointPolicy.validate(getS3BucketListRequest.getEndPoint());
		String region = resolveRegion(getS3BucketListRequest.getRegion(), endpoint.uri().getHost());

		List<Bucket> buckets;
		try (S3Client s3Client = clientFactory.create(endpoint, region, accessKey, secretKey)) {
			buckets = s3Client.listBuckets().buckets();
		} catch (Exception e) {
			// Upstream authentication/connectivity failures are recoverable configuration errors.
			// Do not include SDK messages (which can contain endpoints or signed headers).
			throw new BizException("获取 S3 存储桶失败，请检查端点、区域和访问凭据");
		}

		for (Bucket bucket : buckets) {
			S3BucketNameResult s3BucketNameResult = new S3BucketNameResult(bucket.name(), Date.from(bucket.creationDate()));
			bucketNameList.add(s3BucketNameResult);
		}

		return AjaxJson.getSuccessData(bucketNameList);
	}

	@PostMapping("/getCorsConfig")
	@ResponseBody
	@ApiOperationSupport(order = 1)
	@Operation(summary = "获取 S3 跨域设置")
	public AjaxJson<List<TlgxyCloudCORSRule>> getCorsConfig(@Valid @RequestBody GetS3CorsListRequest getS3CorsListRequest) {
		String accessKey = getS3CorsListRequest.getAccessKey();
		String secretKey = getS3CorsListRequest.getSecretKey();
		var endpoint = endpointPolicy.validate(getS3CorsListRequest.getEndPoint());
		String region = resolveRegion(getS3CorsListRequest.getRegion(), endpoint.uri().getHost());
		String bucketName = getS3CorsListRequest.getBucketName();
		if (StringUtils.isBlank(bucketName) || !bucketName.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")) {
			throw new BizException("请填写有效的存储桶名称");
		}

		try (S3Client s3Client = clientFactory.create(endpoint, region, accessKey, secretKey)) {
			GetBucketCorsResponse getBucketCorsResponse = s3Client.getBucketCors(builder -> builder.bucket(bucketName));
			List<CORSRule> rules = getBucketCorsResponse.corsRules();
			List<TlgxyCloudCORSRule> rulesList = TlgxyCloudCORSRule.fromCORSRule(rules);
			return AjaxJson.getSuccessData(rulesList);
		} catch (S3Exception s3Exception) {
			if (s3Exception.statusCode() == 404) {
				return AjaxJson.getSuccessData(Collections.emptyList());
			} else {
				throw new BizException("获取 S3 跨域设置失败，请检查访问凭据和存储桶权限");
			}
		} catch (Exception e) {
			throw new BizException("获取 S3 跨域设置失败，请检查端点、区域和访问凭据");
		}

	}

	private String resolveRegion(String suppliedRegion, String host) {
		if (StringUtils.isNotBlank(suppliedRegion)) {
			if (!suppliedRegion.matches("[a-zA-Z0-9-]{1,64}")) {
				throw new BizException("S3 区域格式不正确");
			}
			return suppliedRegion;
		}
		// Infer known AWS endpoint regions only; arbitrary custom domains are not regions.
		if (host.matches("s3\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?")) return host.split("\\.")[1];
		return "us-east-1";
	}

}
