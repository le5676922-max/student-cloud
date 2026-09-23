package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.module.storage.model.bo.RefreshTokenCacheBO;
import im.tlgxy.cloud.module.storage.model.dto.RefreshTokenInfoDTO;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.DogeCloudParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import im.tlgxy.cloud.module.storage.service.base.RefreshTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 多吉云（DogeCloud）存储服务实现类。
 * <p>
 * 继承自 {@link AbstractS3BaseFileService}，通过 S3 兼容协议与多吉云对象存储进行交互。
 * 同时实现了 {@link RefreshTokenService} 接口，支持 S3 临时凭证的自动刷新。
 * 多吉云使用临时 STS 凭证机制，需要定期通过 API 获取新的 S3 临时密钥（AccessKey、SecretKey、SessionToken）。
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
public class DogeCloudServiceImpl extends AbstractS3BaseFileService<DogeCloudParam> implements RefreshTokenService {

    /**
     * AWS 凭证对象，用于 S3 客户端的认证
     */
    private AwsCredentials awsCredentials;

    /**
     * 初始化多吉云存储服务。
     * <p>
     * 首先刷新获取临时访问凭证，然后构建 S3 兼容客户端和预签名器。
     * Region 设置为 "automatic" 以自动匹配 endpoint。
     * 使用自定义的凭证提供器 {@link #checkExpiredAndGetAwsCredentials()} 实现凭证的自动刷新。
     * </p>
     */
    @Override
    public void init() {
        // 先刷新获取临时访问凭证
        refreshAccessToken();
        // 多吉云使用 "automatic" region
        Region oss = Region.of("automatic");
        URI endpointOverride = URI.create(param.getEndPoint());

        // 构建 S3 客户端，使用自定义凭证提供器以支持凭证自动刷新
        super.s3ClientNew = S3Client.builder()
                .overrideConfiguration(getClientConfiguration())
                .serviceConfiguration(getS3Configuration())
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(this::checkExpiredAndGetAwsCredentials)
                .build();

        // 构建 S3 预签名器，使用自定义凭证提供器以支持凭证自动刷新
        super.s3Presigner = S3Presigner.builder()
                .region(oss)
                .endpointOverride(endpointOverride)
                .credentialsProvider(this::checkExpiredAndGetAwsCredentials)
                .build();
    }

    /**
     * 返回当前存储类型枚举。
     *
     * @return 存储类型为多吉云
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.DOGE_CLOUD;
    }

    /**
     * 刷新多吉云 S3 临时访问凭证。
     * <p>
     * 调用多吉云 API（/auth/tmp_token.json）获取 S3 临时密钥，
     * 包括 accessKeyId、secretAccessKey 和 sessionToken。
     * 同时从响应中获取 Bucket 和 Endpoint 信息并更新配置参数。
     * 刷新成功后将凭证信息存入缓存，失败则记录失败状态。
     * </p>
     *
     * @throws SystemException 当获取临时密钥失败时抛出
     */
    @Override
    public void refreshAccessToken() {
        try {
            // 构建请求参数：通道类型和存储空间名称
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("channel", "OSS_FULL");
            jsonObject.put("scopes", param.getOriginBucketName());

            // 多吉云 API 地址和路径
            String apiDomain = "https://api.dogecloud.com";
            String apiPath = "/auth/tmp_token.json";

            // 序列化请求参数并计算签名
            String jsonString = jsonObject.toJSONString();
            String token = getToken(apiPath, jsonString);

            if (log.isDebugEnabled()) {
                log.debug("{} 尝试获取 S3 临时密钥, 请求参数: {}", getStorageSimpleInfo(), param);
            }

            // 发送 POST 请求获取临时密钥
            HttpResponse httpResponse = HttpUtil.createPost(apiDomain + apiPath)
                    .body(jsonString)
                    .header(Header.AUTHORIZATION, "TOKEN " + param.getAccessKey() + ":" + token)
                    .execute();

            String body = httpResponse.body();
            int responseStatus = httpResponse.getStatus();

            // 解析响应，检查业务状态码
            JSONObject resultJsonObject = JSONObject.parseObject(body);
            if (resultJsonObject.getInteger("code") != 200){
                log.error("{} 获取 S3 临时密钥失败, 响应头: {}", getStorageSimpleInfo(), body);
                throw new BizException(resultJsonObject.getString("msg"));
            }

            if (log.isDebugEnabled()) {
                log.debug("{} 获取 S3 临时密钥完成. 响应状态码: {}, 响应体: {}", getStorageSimpleInfo(), responseStatus, body);
            }

            // 从响应中解析临时凭证信息
            JSONObject data = resultJsonObject.getJSONObject("data");
            JSONObject credentials = data.getJSONObject("Credentials");
            String s3AccessKey = credentials.getString("accessKeyId");
            String s3SecretKey = credentials.getString("secretAccessKey");
            String s3SessionToken = credentials.getString("sessionToken");
            Integer expiredAt = data.getInteger("ExpiredAt");

            // 从响应中获取 Bucket 信息并更新配置
            JSONArray bucketsArray = data.getJSONArray("Buckets");
            if (bucketsArray == null || bucketsArray.isEmpty()) {
                throw new SystemException("存储空间名称不存在");
            }
            JSONObject buckets = bucketsArray.getJSONObject(0);
            // 更新 Bucket 名称和 Endpoint（API 返回的实际值可能与配置不同）
            param.setBucketName(buckets.getString("s3Bucket"));
            param.setEndPoint(buckets.getString("s3Endpoint"));

            // 构建 Token 信息并存入缓存
            RefreshTokenInfoDTO tokenInfoDTO = RefreshTokenInfoDTO.success(s3AccessKey, s3SecretKey, s3SessionToken, expiredAt);
            RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.success(tokenInfoDTO));

            // 创建 AWS 会话凭证对象
            awsCredentials = AwsSessionCredentials.create(s3AccessKey, s3SecretKey, s3SessionToken);
        } catch (Exception e) {
            // 刷新失败时记录失败状态到缓存
            RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.fail("AccessToken 刷新失败: " + e.getMessage()));
            throw new SystemException("存储源 " + storageId + " 刷新令牌失败, 获取时发生异常.", e);
        }
    }

    /**
     * 计算多吉云 API 请求的签名 Token。
     * <p>
     * 签名算法：对 API 路径和请求体拼接的字符串进行 HmacSHA1 签名，然后转为十六进制字符串。
     * 签名字符串格式：{@code apiPath + "\n" + paramsText}
     * </p>
     *
     * @param apiPath    API 请求路径
     * @param paramsText 请求体 JSON 字符串
     * @return 十六进制格式的签名 Token
     */
    private String getToken(String apiPath, String paramsText) {
        // 拼接签名字符串：路径 + 换行符 + 参数
        String signStr = apiPath + "\n" + paramsText;
        // 使用 SecretKey 对签名字符串进行 HmacSHA1 签名并转为十六进制
        return SecureUtil.hmacSha1(param.getSecretKey()).digestHex(signStr);
    }

    /**
     * 检查 AccessToken 是否过期，并获取最新的 AwsCredentials。
     * <p>
     * 该方法作为 S3 客户端的凭证提供器使用。每次 S3 客户端需要认证时，
     * 会调用此方法获取当前有效的凭证。如果凭证不存在或已过期，
     * 则使用双重检查锁定（DCL）机制确保线程安全地刷新凭证。
     * </p>
     *
     * @return 当前有效的 AWS 凭证
     * @throws SystemException 当凭证刷新失败且无可用凭证时抛出
     */
    private AwsCredentials checkExpiredAndGetAwsCredentials() {
        // 从缓存中获取刷新令牌信息
        RefreshTokenCacheBO.RefreshTokenInfo refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);

        // 如果凭证不存在或已过期，需要刷新
        if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
            // 使用双重检查锁定机制，确保同一个 storageId 只会有一个线程在刷新 AccessToken
            synchronized (("storage-refresh-" + storageId).intern()) {
                // 双重检查，再次从缓存中获取，确认是否其他线程已经刷新过
                refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
                if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
                    log.info("{} S3 临时密钥未获取或已过期, 尝试刷新.", getStorageSimpleInfo());
                    refreshAccessToken();
                    refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
                }
            }
        }

        // 刷新后仍为空则抛出异常
        if (refreshTokenInfo == null) {
            throw new SystemException("存储源 " + storageId + " AccessToken 刷新失败: 未找到刷新令牌信息.");
        }

        return awsCredentials;
    }

}