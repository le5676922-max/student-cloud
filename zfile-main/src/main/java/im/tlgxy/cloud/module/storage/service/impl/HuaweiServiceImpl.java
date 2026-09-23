package im.tlgxy.cloud.module.storage.service.impl;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.UrlUtils;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.HuaweiParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 华为云对象存储（OBS）服务实现类。
 * <p>
 * 继承自 {@link AbstractS3BaseFileService}，通过 S3 兼容协议与华为云 OBS（Object Storage Service）
 * 进行交互。华为云 OBS 兼容 AWS S3 协议，因此可以直接使用 AWS SDK 进行操作。
 * 主要负责初始化 OBS 客户端和预签名器，其他文件操作（文件列表、上传、下载等）由父类 {@link AbstractS3BaseFileService} 提供。
 * </p>
 * <p>
 * Region 固定为 "obs"，与华为云 OBS 的 S3 兼容 endpoint 对应。
 * </p>
 * <p>
 * 作用域为 prototype，每次注入时创建新的实例，确保不同存储源的参数相互隔离。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class HuaweiServiceImpl extends AbstractS3BaseFileService<HuaweiParam> {

    /**
     * 初始化华为云 OBS 客户端。
     * <p>
     * 根据配置参数构建 S3 兼容客户端和预签名器，包括：
     * 1. 处理 endpoint 的协议前缀（如果未指定则自动拼接）
     * 2. 构建 AWS S3 客户端（兼容华为云 OBS）
     * 3. 构建 S3 预签名器
     * 4. 设置上传相关的跨域配置
     * </p>
     * <p>
     * 华为云 OBS 在 S3 兼容模式下的 Region 固定为 "obs"。
     * </p>
     */
    @Override
    public void init() {
        String endPoint = param.getEndPoint();
        String endPointScheme = param.getEndPointScheme();
        // 如果 endPoint 不包含协议部分, 且配置了 endPointScheme, 则手动拼接协议部分.
        if (!UrlUtils.hasScheme(endPoint) && StringUtils.isNotBlank(endPointScheme)) {
            endPoint = endPointScheme + "://" + endPoint;
        }

        // 华为云 OBS 在 S3 兼容模式下的 Region 固定为 "obs"
        Region oss = Region.of("obs");
        URI endpointOverride = URI.create(endPoint);
        // 使用 AccessKey 和 SecretKey 构建静态凭证提供器
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(param.getAccessKey(), param.getSecretKey()));

        // 构建 S3 客户端，配置 endpoint、region 和凭证
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

        // 设置上传 CORS 规则
        setUploadCors();
    }

    /**
     * 返回当前存储类型枚举。
     *
     * @return 存储类型为华为云 OBS
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.HUAWEI;
    }

}