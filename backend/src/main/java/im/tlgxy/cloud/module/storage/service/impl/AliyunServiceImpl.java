package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.net.url.UrlPath;
import cn.hutool.core.util.URLUtil;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.UrlUtils;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.AliyunParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.net.util.Base64;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;

/**
 * 阿里云OSS存储服务实现类。
 * <p>
 * 继承自 {@link AbstractS3BaseFileService}，通过 S3 兼容协议与阿里云对象存储 OSS 进行交互。
 * 负责初始化阿里云 OSS 客户端、生成预签名下载链接等功能。
 * 因为阿里云 OSS 的签名算法与标准 S3 不同，所以自定义了内部签名器 {@link Signer}。
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
public class AliyunServiceImpl extends AbstractS3BaseFileService<AliyunParam> {

    /**
     * 阿里云 OSS 自定义签名器，用于生成预签名 URL
     */
    private Signer signer;

    /**
     * 初始化阿里云 OSS 客户端。
     * <p>
     * 根据配置参数构建 S3 兼容客户端和预签名器，包括：
     * 1. 处理 endpoint 的协议前缀（如果未指定则自动拼接）
     * 2. 初始化自定义签名器
     * 3. 构建 AWS S3 客户端（兼容阿里云 OSS）
     * 4. 构建 S3 预签名器
     * 5. 设置上传相关的跨域配置
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
        // 创建自定义签名器，用于生成阿里云 OSS 特有的预签名 URL
        signer = new Signer(param.getAccessKey(), param.getSecretKey(), endPoint);

        // 阿里云 OSS 的 S3 兼容 Region 固定为 "oss"
        Region oss = Region.of("oss");
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

        // Initializing/saving a connection must never change remote bucket policies.
    }

    /**
     * 返回当前存储类型枚举。
     *
     * @return 存储类型为阿里云 OSS
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.ALIYUN;
    }

    /**
     * 根据文件路径和名称生成下载链接。
     * <p>
     * 生成逻辑：
     * 1. 如果启用了代理下载且没有自定义域名，直接返回代理下载地址
     * 2. 如果是公开空间且有自定义域名，直接拼接返回
     * 3. 如果是私有空间，生成带签名的预签名 URL（默认有效期 1800 秒）
     * 4. 如果有自定义域名，替换签名 URL 中的 host 为自定义域名
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件的下载 URL（可能是直接链接或预签名链接）
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        // 启用了代理下载且没有自定义域名时，使用代理下载
        if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getDomain())) {
            return getProxyDownloadUrl(pathAndName, false);
        }
        String bucketName = param.getBucketName();
        String domain = param.getDomain();
        String basePath = param.getBasePath();

        // 拼接基础路径和文件路径，去除开头的斜杠
        String fullPath = StringUtils.concatTrimStartSlashes(basePath, pathAndName);

        // 如果不是私有空间, 且指定了加速域名, 则直接返回下载地址（无需签名）
        if (BooleanUtils.isNotTrue(param.isPrivate()) && StringUtils.isNotEmpty(domain)) {
            return StringUtils.concat(domain, StringUtils.encodeAllIgnoreSlashes(fullPath));
        }

        // 签名有效期默认为 1800 秒（30分钟）
        Integer tokenTime = param.getTokenTime();
        if (param.getTokenTime() == null || param.getTokenTime() < 1) {
            tokenTime = 1800;
        }

        // 计算签名过期时间
        Date expiration = new Date(System.currentTimeMillis() + tokenTime * 1000);
        // 使用自定义签名器生成预签名 URL
        String defaultUrl = signer.generatePresignedUrl(bucketName, fullPath, HttpMethod.GET, expiration);

        // 如果配置了自定义域名，将签名 URL 中的 host 替换为自定义域名
        if (StringUtils.isNotEmpty(domain)) {
            defaultUrl = StringUtils.replaceHost(defaultUrl, domain);
        }
        return defaultUrl;
    }

    /**
     * 阿里云 OSS 自定义签名器。
     * <p>
     * 阿里云 OSS 使用特有的签名算法（HmacSHA1），与 AWS S3 标准的签名方式不同，
     * 因此需要使用自定义签名器来生成预签名 URL。
     * </p>
     */
    static class Signer {

        /**
         * OSS endpoint 地址
         */
        private final String endPoint;

        /**
         * 阿里云 AccessKey
         */
        private final String accessKey;

        /**
         * 阿里云 SecretKey
         */
        private final String secretKey;

        /**
         * 构造签名器。
         *
         * @param accessKey 阿里云 AccessKey
         * @param secretKey 阿里云 SecretKey
         * @param endPoint  OSS endpoint 地址
         */
        public Signer(String accessKey, String secretKey, String endPoint) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.endPoint = endPoint;
        }

        /**
         * 使用 HmacSHA1 算法对数据进行签名，返回 Base64 编码的签名字符串。
         *
         * @param data 待签名的数据
         * @return Base64 编码的签名字符串，签名失败返回 null
         */
        private String sign(String data) {
            try {
                // 使用 HmacSHA1 算法生成签名
                SecretKeySpec signingKey = new SecretKeySpec(secretKey.getBytes(), "HmacSHA1");
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(signingKey);
                return Base64.encodeBase64StringUnChunked(mac.doFinal(data.getBytes()));
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (InvalidKeyException e) {
                e.printStackTrace();
            }
            return null;
        }


        /**
         * 生成阿里云 OSS 预签名 URL。
         * <p>
         * 按照阿里云 OSS 的签名规则，构造包含 AccessKeyId、Expires 和 Signature 参数的 URL。
         * 签名目标格式为：{@code HTTP_METHOD\n\n\nExpires\n/BucketName/ObjectPath}
         * </p>
         *
         * @param bucketName Bucket 名称
         * @param key        文件路径（Object Key）
         * @param method     HTTP 请求方法
         * @param expiration 签名过期时间
         * @return 带有签名的预签名 URL
         */
        private String generatePresignedUrl(String bucketName, String key, HttpMethod method, Date expiration) {
            // 过期时间转换为秒级时间戳
            long expirationLong = expiration.getTime() / 1000;

            // 构建 URL：scheme://bucketName.endpoint/path
            UrlBuilder urlBuilder = UrlBuilder.of()
                    .setScheme(UrlUtils.getSchema(endPoint))
                    .setHost(bucketName + "." + UrlUtils.removeScheme(endPoint))
                    .setPath(UrlPath.of(key, StandardCharsets.UTF_8))
                    .addQuery("Expires", expirationLong)
                    .addQuery("OSSAccessKeyId", accessKey);

            // 拼接待签名的资源路径
            String url = StringUtils.concat(bucketName, key);

            // 构建待签名的字符串：HTTP_METHOD\n\n\nExpires\nResourcePath
            String data = method.toString() + "\n\n\n" + expirationLong + "\n" + url;
            // 对签名数据进行 HmacSHA1 签名
            String signature = sign(data);
            // 将签名添加到 URL 查询参数中
            urlBuilder.addQuery("Signature", URLUtil.encodeAll(signature));

            // 手动编码路径, 防止签名中的特殊字符被 URL 编码
            StringBuilder encodePath = new StringBuilder();
            List<String> split = StringUtils.split(urlBuilder.getPath().toString(), "/", false, true);
            for (String s : split) {
                encodePath.append("/").append(URLUtil.encodeAll(s));
            }

            // 拼接最终 URL：scheme://host/encodedPath?query
            return urlBuilder.getScheme() + "://" + urlBuilder.getHost() + encodePath + "?" + urlBuilder.getQuery();
        }
    }

}
