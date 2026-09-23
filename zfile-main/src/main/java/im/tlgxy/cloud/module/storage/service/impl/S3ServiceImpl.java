package im.tlgxy.cloud.module.storage.service.impl;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.UrlUtils;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.S3Param;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 通用 S3 兼容协议对象存储服务实现类。
 * <p>
 * 基于 AWS S3 SDK v2，支持所有兼容 S3 协议的对象存储服务
 * （如 MinIO、阿里云 OSS、腾讯云 COS 等）。
 * 提供了 Path-Style 和 Virtual-Hosted-Style 两种访问方式，
 * 支持自定义域名和区域配置。
 * </p>
 * <p>
 * 该 Bean 为原型作用域（PROTOTYPE），每次注入都会创建新实例，
 * 以便支持多个不同的存储源配置同时存在。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class S3ServiceImpl extends AbstractS3BaseFileService<S3Param> {

    /**
     * 初始化 S3 兼容存储服务。
     * <p>
     * 主要完成以下工作：
     * <ol>
     *   <li>处理 endpoint 地址的协议前缀</li>
     *   <li>校验域名风格与访问方式的一致性</li>
     *   <li>解析并设置区域（Region）信息</li>
     *   <li>创建 S3 Client（用于文件操作）</li>
     *   <li>创建两个 S3 Presigner：
     *       <ul>
     *         <li>s3PresignerDownload：下载专用，当配置了自定义域名时使用域名作为 endpoint</li>
     *         <li>s3Presigner：通用预签名器，使用原始 endpoint</li>
     *       </ul>
     *   </li>
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

        // 判断是否为 Path-Style 访问方式（URL 中将 bucket 作为路径的一部分）
        boolean isPathStyle = "path-style".equals(param.getPathStyle());
        // 获取自定义域名配置
        String domain = param.getDomain();
        // 当使用自定义域名访问时，只能使用 Path-Style 模式，否则抛出业务异常
        if (StringUtils.isNotBlank(domain) && !isPathStyle) {
            throw new BizException("当使用域名访问时, 域名风格只能使用路径模式, 请修改存储配置中的域名风格选项.");
        }

        // 解析区域信息：优先使用配置的 region，否则从 endpoint 中提取
        String region = param.getRegion();
        if (StringUtils.isEmpty(param.getRegion()) && StringUtils.isNotEmpty(endPoint)) {
            // 从 endpoint 地址中提取区域信息（如 s3.us-east-1.amazonaws.com 中的 us-east-1）
            region = endPoint.split("\\.")[1];
        }

        // 创建 AWS Region 对象
        Region oss = Region.of(region);
        // 构建 endpoint 的 URI 对象，用于覆盖默认的 S3 终端节点
        URI endpointOverride = URI.create(endPoint);
        // 基于 AccessKey 和 SecretKey 创建静态 AWS 凭证提供器
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(param.getAccessKey(), param.getSecretKey()));

        // 构建 S3 客户端（用于文件的上传、下载、列表等操作）
        // 启用 forcePathStyle 以支持 Path-Style 访问方式
        super.s3ClientNew = S3Client.builder()
                .overrideConfiguration(getClientConfiguration())
                .serviceConfiguration(getS3Configuration())
                .forcePathStyle(isPathStyle)
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(credentialsProvider)
                .build();

        // 构建下载专用的 S3 预签名器
        // 当配置了自定义域名时，使用域名替代原始 endpoint，以确保下载链接使用自定义域名
        super.s3PresignerDownload = S3Presigner.builder()
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(isPathStyle)
                        .build())
                .region(oss)
                .endpointOverride(StringUtils.isBlank(domain) ? endpointOverride : URI.create(domain))
                .credentialsProvider(credentialsProvider)
                .build();

        // 构建通用的 S3 预签名器（用于生成预签名 URL）
        super.s3Presigner = S3Presigner.builder()
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(isPathStyle)
                        .build())
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(credentialsProvider)
                .build();

        // 设置跨域上传规则，允许浏览器端直传
        setUploadCors();
    }

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#S3}，表示通用 S3 兼容存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.S3;
    }

}