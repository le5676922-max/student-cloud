package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.exceptions.ExceptionUtil;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.util.Auth;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.UrlUtils;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.QiniuParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 七牛云对象存储 (Kodo) 服务实现类。
 * <p>
 * 基于七牛云 S3 兼容 API 和七牛云原生 SDK，提供文件上传、下载、重命名等操作。
 * 继承自 {@link AbstractS3BaseFileService}，复用 S3 兼容协议的基础逻辑，
 * 同时针对七牛云特有的私有空间签名下载等功能使用七牛云原生 SDK 实现。
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
public class QiniuServiceImpl extends AbstractS3BaseFileService<QiniuParam> {

    /**
     * 七牛云存储空间管理器，用于执行文件移动、删除等空间管理操作
     */
    private BucketManager bucketManager;

    /**
     * 七牛云认证凭证，用于生成私有空间下载链接的签名
     */
    private Auth auth;

    /**
     * 初始化七牛云存储服务。
     * <p>
     * 主要完成以下工作：
     * <ol>
     *   <li>处理 endpoint 地址的协议前缀（如果未配置则根据 endPointScheme 拼接）</li>
     *   <li>通过 S3 兼容 SDK 创建 {@link S3Client} 和 {@link S3Presigner}</li>
     *   <li>通过七牛云原生 SDK 创建 {@link Auth} 和 {@link BucketManager}</li>
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

        // 七牛云 S3 兼容接口使用的区域标识为 "kodo"
        software.amazon.awssdk.regions.Region oss = software.amazon.awssdk.regions.Region.of("kodo");
        // 构建 endpoint 的 URI 对象，用于覆盖默认的 S3 终端节点
        URI endpointOverride = URI.create(endPoint);
        // 基于七牛云的 AccessKey 和 SecretKey 创建静态 AWS 凭证提供器
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(param.getAccessKey(), param.getSecretKey()));

        // 构建 S3 客户端，设置区域、终端节点和凭证
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

        // 初始化七牛云原生 SDK 的配置和认证对象
        Configuration cfg = new Configuration(Region.autoRegion());
        auth = Auth.create(param.getAccessKey(), param.getSecretKey());
        bucketManager = new BucketManager(auth, cfg);

        // 设置跨域上传规则，允许浏览器端直传
        // CORS is an explicit administrative operation, not an initialization side effect.
    }

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#QINIU}，表示七牛云存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.QINIU;
    }

    /**
     * 重命名（移动）文件。
     * <p>
     * 通过七牛云原生 SDK 的 {@link BucketManager#move} 方法实现文件重命名，
     * 将源路径的文件移动到目标路径。
     * </p>
     *
     * @param path    文件所在目录路径
     * @param name    文件当前名称
     * @param newName 文件新名称
     * @return 重命名成功返回 {@code true}
     * @throws RuntimeException 如果七牛云 API 调用失败，则包装为运行时异常抛出
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        String bucketName = param.getBucketName();
        String basePath = param.getBasePath();

        // 拼接完整的源文件路径：基础路径 + 用户基础路径 + 目录路径 + 当前文件名
        String srcPath = StringUtils.concat(basePath, getCurrentUserBasePath(), path, name);
        srcPath = StringUtils.trimStartSlashes(srcPath);

        // 拼接完整的目标文件路径：基础路径 + 用户基础路径 + 目录路径 + 新文件名
        String distPath = StringUtils.concat(basePath, getCurrentUserBasePath(), path, newName);
        distPath = StringUtils.trimStartSlashes(distPath);

        try {
            // 调用七牛云原生 SDK 执行文件移动（重命名）操作
            bucketManager.move(bucketName, srcPath, bucketName, distPath);
            return true;
        } catch (QiniuException e) {
            // 将七牛云异常包装为运行时异常抛出
            throw ExceptionUtil.wrapRuntime(e);
        }

    }

    /**
     * 获取文件的下载链接。
     * <p>
     * 根据存储空间的配置，采用不同的策略生成下载链接：
     * <ul>
     *   <li>如果启用了代理下载且未配置自定义域名，则返回代理下载链接</li>
     *   <li>如果是私有空间且配置了加速域名，则通过七牛云原生 SDK 生成带签名的私有下载链接</li>
     *   <li>其他情况使用父类的 S3 兼容方式生成下载链接</li>
     * </ul>
     * 注意：使用 S3 SDK 获取到的下载链接在替换为加速域名后可能无法访问，
     * 因此私有空间场景下使用七牛云原生 SDK 生成签名链接。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件的下载 URL
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        // 如果开启了代理下载且未配置自定义域名，则使用代理下载链接
        if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getDomain())) {
            return getProxyDownloadUrl(pathAndName, false);
        }
        String domain = param.getDomain();

        // 设置签名链接的有效时间（秒），默认为 1800 秒（30分钟）
        Integer tokenTime = param.getTokenTime();
        if (param.getTokenTime() == null || param.getTokenTime() < 1) {
            tokenTime = 1800;
        }

        // 拼接完整文件路径并处理斜杠
        String fullPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), pathAndName);
        // 如果不是私有空间, 且指定了加速域名, 则使用 qiniu 的 sdk 获取下载链接
        // (使用 s3 sdk 获取到的下载链接替换自动加速域名后无法访问, 故这里使用 qiniu sdk)
        if (BooleanUtils.isTrue(param.isPrivate()) && StringUtils.isNotEmpty(domain)) {
            // 拼接自定义域名和文件路径，生成带签名的私有下载链接
            String customDomainFullPath = StringUtils.removeDuplicateSlashes(domain + StringUtils.SLASH + StringUtils.encodeAllIgnoreSlashes(fullPath));
            return auth.privateDownloadUrl(customDomainFullPath, tokenTime);
        }

        // 其他情况使用父类 S3 兼容方式生成下载链接
        return super.getDownloadUrl(pathAndName);
    }
}
