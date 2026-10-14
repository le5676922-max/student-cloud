package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.convert.Convert;
import im.tlgxy.cloud.core.util.NumberUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.UrlUtils;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.TencentParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.function.Consumer;

/**
 * 腾讯云对象存储 (COS - Cloud Object Storage) 服务实现类。
 * <p>
 * 基于腾讯云 COS 的 S3 兼容 API，通过 AWS S3 SDK v2 实现文件的上传、
 * 下载、列表等操作。腾讯云 COS 使用自定义的区域标识 "cos"，
 * 通过覆盖 S3 默认 endpoint 实现与腾讯云 API 的对接。
 * </p>
 * <p>
 * 该 Bean 为原型作用域（PROTOTYPE），每次注入都会创建新实例，
 * 以便支持多个不同的存储桶配置同时存在。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class TencentServiceImpl extends AbstractS3BaseFileService<TencentParam> {

    /**
     * 初始化腾讯云 COS 存储服务。
     * <p>
     * 主要完成以下工作：
     * <ol>
     *   <li>处理 endpoint 地址的协议前缀</li>
     *   <li>使用区域标识 "cos" 和腾讯云 endpoint 创建 S3 客户端和预签名器</li>
     *   <li>设置跨域（CORS）上传规则</li>
     * </ol>
     * </p>
     */
    @Override
    public void init() {
        // 获取配置的 endpoint 地址
        String endPoint = param.getEndPoint();
        String endPointScheme = param.getEndPointScheme();
        // 如果 endPoint 不包含协议部分, 且配置了 endPointScheme, 则手动拼接协议部分
        if (!UrlUtils.hasScheme(endPoint) && StringUtils.isNotBlank(endPointScheme)) {
            endPoint = endPointScheme + "://" + endPoint;
        }

        // 腾讯云 COS 的 S3 兼容接口使用 "cos" 作为区域标识
        Region oss = Region.of("cos");
        // 构建 endpoint 的 URI 对象，用于覆盖默认的 S3 终端节点（指向腾讯云 COS 端点）
        URI endpointOverride = URI.create(endPoint);
        // 基于腾讯云的 SecretId 和 SecretKey 创建静态 AWS 凭证提供器
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(param.getAccessKey(), param.getSecretKey()));

        // 构建 S3 客户端，设置区域 "cos"、腾讯云终端节点和凭证
        super.s3ClientNew = S3Client.builder()
                .overrideConfiguration(getClientConfiguration())
                .serviceConfiguration(getS3Configuration())
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(credentialsProvider)
                .build();

        // 构建 S3 预签名器，用于生成预签名 URL
        super.s3Presigner = S3Presigner.builder()
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(credentialsProvider)
                .build();

        // 设置跨域上传规则，允许浏览器端直传
        // CORS is an explicit administrative operation, not an initialization side effect.
    }

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#TENCENT}，表示腾讯云 COS 存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.TENCENT;
    }

}
