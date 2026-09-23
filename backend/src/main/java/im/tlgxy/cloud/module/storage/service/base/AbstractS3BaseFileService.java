package im.tlgxy.cloud.module.storage.service.base;

import cn.hutool.core.convert.Convert;
import com.alibaba.fastjson2.JSON;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.CorsBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.RequestUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.constant.StorageSourceConnectionProperties;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.dto.TlgxyCloudCORSRule;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.S3BaseParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * S3 兼容存储源抽象基础服务类。
 * <p>
 * 该类封装了与 S3 兼容对象存储（如 Amazon S3、MinIO、阿里云 OSS 等）交互的核心逻辑，包括：
 * <ul>
 *   <li>文件和文件夹的列表、获取、创建、删除、复制、移动、重命名等基本操作</li>
 *   <li>基于预签名 URL 的文件上传和下载</li>
 *   <li>自定义域名的支持（使用自定义域名替换 S3 默认地址）</li>
 *   <li>CORS 跨域配置的设置</li>
 *   <li>支持分页获取文件列表（每次最多 1000 个对象）</li>
 * </ul>
 * </p>
 * <p>
 * 子类需要初始化 {@link #s3ClientNew}、{@link #s3Presigner} 等 S3 客户端实例。
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承自 {@link S3BaseParam}
 * @author zhaojun
 */
@Slf4j
public abstract class AbstractS3BaseFileService<P extends S3BaseParam> extends AbstractProxyTransferService<P> {

    /**
     * S3 客户端实例，用于与 S3 兼容存储服务进行交互。
     * 子类需要在初始化时创建并赋值。
     */
    protected S3Client s3ClientNew;

    /**
     * S3 预签名器实例，用于生成预签名的上传/下载 URL。
     */
    protected S3Presigner s3Presigner;

    /**
     * S3 下载专用的预签名器实例。
     * 如果为 null，则使用 {@link #s3Presigner} 替代。
     */
    protected S3Presigner s3PresignerDownload;

    /**
     * 空的输入流常量，用于创建空文件或占位。
     */
    public static final InputStream EMPTY_INPUT_STREAM = new ByteArrayInputStream(new byte[0]);

    /**
     * 取消超时限制的构建器消费者，将 API 调用超时设置为 30 天（实际等于不超时），
     * 用于大文件上传/下载等长时间操作。
     */
    Consumer<AwsRequestOverrideConfiguration.Builder> unlimitTimeoutBuilderConsumer = builder -> builder.apiCallTimeout(Duration.ofDays(30)).build();

    /**
     * 获取指定文件夹路径下的文件和文件夹列表。
     * <p>
     * 直接委托给 {@link #s3FileList(String)} 方法执行。
     * </p>
     *
     * @param folderPath 文件夹路径
     * @return 文件和文件夹列表
     */
    @Override
    public List<FileItemResult> fileList(String folderPath) {
        return s3FileList(folderPath);
    }


    /**
     * 获取 S3 对象的下载地址。
     * <p>
     * 生成逻辑：
     * <ol>
     *   <li>如果启用了代理下载且未配置自定义域名，则返回代理下载地址</li>
     *   <li>如果是公开空间且配置了自定义域名，直接拼接返回</li>
     *   <li>否则使用 S3 预签名器生成带过期时间的预签名下载 URL</li>
     *   <li>如果配置了自定义域名，将预签名 URL 中的主机名替换为自定义域名</li>
     * </ol>
     * </p>
     *
     * @param pathAndName 文件路径及文件名称
     * @return S3 对象的访问（下载）地址
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        // 如果启用了代理下载且未配置自定义域名，使用代理下载地址
        if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getDomain())) {
            return getProxyDownloadUrl(pathAndName, false);
        }
        String bucketName = param.getBucketName();
        String domain = param.getDomain();

        String fullPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), pathAndName);

        // 如果不是私有空间, 且指定了加速域名, 则直接拼接返回下载地址（无需预签名）
        if (BooleanUtils.isNotTrue(param.isPrivate()) && StringUtils.isNotEmpty(domain)) {
            return StringUtils.concat(domain, StringUtils.encodeAllIgnoreSlashes(fullPath));
        }

        // 获取 Token 有效期，默认 1800 秒（30分钟）
        Integer tokenTime = param.getTokenTime();
        if (param.getTokenTime() == null || param.getTokenTime() < 1) {
            tokenTime = 1800;
        }

        // 构建 GetObjectRequest，并允许子类自定义请求参数
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .applyMutation(processGeneratePresignedUrlRequest())
                .bucket(bucketName)
                .key(fullPath)
                .build();

        // 使用 S3 预签名器生成预签名下载 URL
        S3Presigner presigner = s3PresignerDownload != null ? s3PresignerDownload : s3Presigner;
        PresignedGetObjectRequest presignedGetObjectRequest = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .getObjectRequest(getObjectRequest)
                .signatureDuration(Duration.ofSeconds(tokenTime))
                .build());
        URL url = presignedGetObjectRequest.url();
        String defaultUrl = url.toExternalForm();

        // 如果配置了自定义域名，替换预签名 URL 中的主机名
        if (StringUtils.isNotEmpty(domain)) {
            String path = url.getFile();
            // S3 标准实现中 URL 路径包含 bucketName，需要移除
            if (this instanceof S3ServiceImpl) {
                path = path.replaceFirst(bucketName + "/", "");
            }
            defaultUrl = StringUtils.concat(domain, path);
        }
        return defaultUrl;
    }

    /**
     * 处理预签名 URL 请求的生成过程，允许子类自定义 GetObjectRequest 的构建参数。
     * <p>
     * 默认实现为空操作，子类可重写以添加自定义的请求参数
     * （如设置特定的请求头、查询参数等）。
     * </p>
     *
     * @return 用于自定义 GetObjectRequest 构建的 Consumer
     */
    public Consumer<GetObjectRequest.Builder> processGeneratePresignedUrlRequest() {
        return builder -> {
        };
    }

    /**
     * 获取 S3 存储桶中指定目录下的对象列表。
     * <p>
     * 使用 S3 ListObjectsV2 API 进行分页查询，每页最多返回 1000 个对象。
     * 同时返回文件（通过 contents）和文件夹/公共前缀（通过 commonPrefixes）。
     * </p>
     *
     * @param path 目录路径（相对于用户根目录）
     * @return 指定目录下的文件和文件夹列表
     */
    public List<FileItemResult> s3FileList(String path) {
        String bucketName = param.getBucketName();
        // 拼接完整路径前缀：basePath + 用户根目录 + 路径 + "/"
        String fullPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), path, StringUtils.SLASH);

        List<FileItemResult> fileItemList = new ArrayList<>();

        // 构建 ListObjectsV2 请求，使用 delimiter="/" 模拟文件夹层级结构
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(fullPath)
                .maxKeys(1000)
                .delimiter(StringUtils.SLASH)
                .build();
        // 使用分页迭代器自动处理分页
        ListObjectsV2Iterable listObjectsV2Iterable = s3ClientNew.listObjectsV2Paginator(listObjectsV2Request);

        // 处理文件对象（contents）
        for (S3Object s : listObjectsV2Iterable.contents()) {
            FileItemResult fileItemResult = new FileItemResult();
            // 跳过目录本身（key 等于前缀路径的情况）
            if (s.key().equals(fullPath)) {
                continue;
            }
            // 从完整 key 中提取文件名（去掉前缀路径）
            fileItemResult.setName(s.key().substring(fullPath.length()));
            fileItemResult.setSize(s.size());
            fileItemResult.setTime(Date.from(s.lastModified()));
            fileItemResult.setType(FileTypeEnum.FILE);
            fileItemResult.setPath(path);

            // 设置下载地址
            String fullPathAndName = StringUtils.concat(getCurrentUserBasePath(), path, fileItemResult.getName());
            fileItemResult.setUrl(getDownloadUrl(fullPathAndName));

            fileItemList.add(fileItemResult);
        }

        // 处理文件夹/公共前缀（commonPrefixes）
        for (CommonPrefix commonPrefix : listObjectsV2Iterable.commonPrefixes()) {
            String commonPrefixStr = commonPrefix.prefix();
            FileItemResult fileItemResult = new FileItemResult();
            // 从公共前缀中提取文件夹名称（去掉前缀路径和末尾的 "/"）
            fileItemResult.setName(commonPrefixStr.substring(fullPath.length(), commonPrefixStr.length() - 1));
            String name = fileItemResult.getName();
            // 过滤掉空名称或斜杠的情况
            if (StringUtils.isEmpty(name) || StringUtils.equals(name, StringUtils.SLASH)) {
                continue;
            }

            fileItemResult.setType(FileTypeEnum.FOLDER);
            fileItemResult.setPath(path);
            fileItemList.add(fileItemResult);
        }

        return fileItemList;
    }

    /**
     * 获取单个文件信息。
     * <p>
     * 通过 S3 HeadObject API 获取文件的元数据信息（大小、最后修改时间等），
     * 不会下载文件内容。
     * </p>
     *
     * @param pathAndName 文件路径及文件名称
     * @return 文件信息，如果文件不存在（NoSuchKeyException）则返回 null
     */
    @Override
    public FileItemResult getFileItem(String pathAndName) {
        String fileName = FileUtils.getName(pathAndName);
        String parentPath = FileUtils.getParentPath(pathAndName);

        // 拼接完整的 S3 对象 key
        String trimStartPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), pathAndName);
        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(param.getBucketName()).key(trimStartPath).build();
        HeadObjectResponse headObjectResponse;
        try {
            // 调用 HeadObject API 获取对象元数据
            headObjectResponse = s3ClientNew.headObject(headObjectRequest);
        } catch (NoSuchKeyException e) {
            // 文件不存在
            return null;
        }

        // 将 S3 对象元数据转换为 FileItemResult
        FileItemResult fileItemResult = new FileItemResult();
        fileItemResult.setName(fileName);
        fileItemResult.setSize(headObjectResponse.contentLength());
        fileItemResult.setTime(Date.from(headObjectResponse.lastModified()));
        fileItemResult.setType(FileTypeEnum.FILE);
        fileItemResult.setPath(parentPath);
        fileItemResult.setUrl(getDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), pathAndName)));
        return fileItemResult;
    }

    /**
     * 在指定路径下创建新文件夹。
     * <p>
     * 在 S3 中，文件夹是通过创建以 "/" 结尾的空对象来模拟的。
     * </p>
     *
     * @param path 文件夹路径
     * @param name 新文件夹名称
     * @return 是否创建成功
     */
    @Override
    public boolean newFolder(String path, String name) {
        name = StringUtils.trimSlashes(name);
        // S3 中文件夹以 "/" 结尾的空对象表示
        String fullPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), path, name, StringUtils.SLASH);
        // 创建空内容的 PutObject 来表示文件夹
        PutObjectResponse putObjectResponse = s3ClientNew.putObject(PutObjectRequest.builder()
                        .bucket(param.getBucketName())
                        .key(fullPath)
                        .build(),
                RequestBody.empty());

        return putObjectResponse != null && putObjectResponse.sdkHttpResponse().isSuccessful();
    }

    /**
     * 删除指定路径下的文件。
     * <p>
     * 通过 S3 DeleteObject API 删除指定的对象。
     * </p>
     *
     * @param path 文件路径
     * @param name 文件名称
     * @return 是否删除成功
     */
    @Override
    public boolean deleteFile(String path, String name) {
        String fullPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), path, name);
        DeleteObjectResponse deleteObjectResponse = s3ClientNew.deleteObject(DeleteObjectRequest.builder()
                .bucket(param.getBucketName())
                .key(fullPath)
                .build());
        return deleteObjectResponse != null && deleteObjectResponse.sdkHttpResponse().isSuccessful();
    }

    /**
     * 删除指定路径下的文件夹。
     * <p>
     * S3 中的文件夹是以 "/" 结尾的空对象，删除方式与文件相同，
     * 只需在名称末尾加上 "/"。
     * </p>
     *
     * @param path 文件夹路径
     * @param name 文件夹名称
     * @return 是否删除成功
     */
    @Override
    public boolean deleteFolder(String path, String name) {
        return deleteFile(path, name + StringUtils.SLASH);
    }

    /**
     * 重命名文件。
     * <p>
     * S3 不支持直接重命名，因此通过"先复制后删除"的方式实现：
     * 先将文件复制到新名称，再删除原文件。
     * </p>
     *
     * @param path    文件路径
     * @param name    文件原名
     * @param newName 新文件名
     * @return 始终返回 true（复制+删除成功）
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        // S3 不支持直接重命名，采用复制+删除策略
        this.copyFile(path, name, path, newName);
        this.deleteFile(path, name);
        return true;
    }

    /**
     * 重命名文件夹。
     * <p>
     * S3 不支持文件夹重命名操作，因此直接抛出异常。
     * </p>
     *
     * @param path    文件夹路径
     * @param name    文件夹原名
     * @param newName 新文件夹名称
     * @throws BizException 始终抛出不支持的异常
     */
    @Override
    public boolean renameFolder(String path, String name, String newName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 获取文件上传地址。
     * <p>
     * 如果启用了代理上传，则返回代理上传地址；
     * 否则使用 S3 预签名器生成预签名上传 URL（有效期 30 分钟）。
     * </p>
     *
     * @param path 文件路径（文件夹路径）
     * @param name 文件名称
     * @param size 文件大小（字节）
     * @return 文件上传地址
     */
    @Override
    public String getUploadUrl(String path, String name, Long size) {
        // 如果启用了代理上传，使用代理上传地址
        if (param.isEnableProxyUpload()) {
            return super.getProxyUploadUrl(path, name);
        }
        String bucketName = param.getBucketName();
        String uploadToPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), path, name);

        // 构建 PutObject 请求
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(uploadToPath)
                .build();

        // 生成预签名上传 URL，有效期 30 分钟
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(30))  // The URL expires in 10 minutes.
                .putObjectRequest(objectRequest)
                .build();


        PresignedPutObjectRequest presignedPutObjectRequest = s3Presigner.presignPutObject(presignRequest);
        URL url = presignedPutObjectRequest.url();
        String urlString = url.toExternalForm();

        // 根据文件扩展名设置 Content-Type 参数
        String contentType = parseContentTypeByName(name, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        urlString = urlString + (urlString.contains("?") ? "&" : "?") + "Content-Type=" + contentType;
        return urlString;
    }


    /**
     * 复制文件到目标路径。
     * <p>
     * 通过 S3 CopyObject API 实现文件复制，源和目标在同一个存储桶中。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名称
     * @param targetPath 目标文件路径
     * @param targetName 目标文件名称
     * @return 是否复制成功
     */
    @Override
    public boolean copyFile(String path, String name, String targetPath, String targetName) {
        String bucketName = param.getBucketName();

        // 拼接源文件和目标文件的完整 S3 key
        String srcFilePath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), path, name);
        String distFilePath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), targetPath, targetName);

        CopyObjectResponse copyObjectResponse = s3ClientNew.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(srcFilePath)
                .destinationBucket(bucketName)
                .destinationKey(distFilePath)
                .build());
        return copyObjectResponse != null && copyObjectResponse.sdkHttpResponse().isSuccessful();
    }

    /**
     * 复制文件夹到目标路径。
     * <p>
     * S3 不支持文件夹复制操作，因此直接抛出异常。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名称
     * @param targetPath 目标文件夹路径
     * @param targetName 目标文件夹名称
     * @throws BizException 始终抛出不支持的异常
     */
    @Override
    public boolean copyFolder(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 移动文件到目标路径。
     * <p>
     * S3 不支持直接移动，因此通过"先复制后删除"的方式实现。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名称
     * @param targetPath 目标文件路径
     * @param targetName 目标文件名称
     * @return 始终返回 true
     */
    @Override
    public boolean moveFile(String path, String name, String targetPath, String targetName) {
        // S3 不支持直接移动，采用复制+删除策略
        this.copyFile(path, name, targetPath, targetName);
        this.deleteFile(path, name);
        return true;
    }

    /**
     * 移动文件夹到目标路径。
     * <p>
     * S3 不支持文件夹移动操作，因此直接抛出异常。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名称
     * @param targetPath 目标文件夹路径
     * @param targetName 目标文件夹名称
     * @throws BizException 始终抛出不支持的异常
     */
    @Override
    public boolean moveFolder(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 设置 S3 存储桶的 CORS（跨域资源共享）规则。
     * <p>
     * 从存储源参数中读取 CORS 配置列表，转换为 S3 CORS 规则并应用到存储桶。
     * 如果配置为空或不合法，将抛出 CorsBizException。
     * </p>
     *
     * @throws CorsBizException 如果设置 CORS 规则失败
     */
    protected void setUploadCors() {
        try {
            // 从存储源参数中解析 CORS 规则配置
            List<TlgxyCloudCORSRule> studentCloudCORSRuleList = JSON.parseArray(param.getCorsConfigList(), TlgxyCloudCORSRule.class);
            if (studentCloudCORSRuleList == null || studentCloudCORSRuleList.isEmpty()) {
                return;
            }
            // 将 TlgxyCloud CORS 规则转换为 S3 CORS 规则
            Set<CORSRule> s3CORSRuleList = TlgxyCloudCORSRule.toCORSRule(studentCloudCORSRuleList);
            CORSConfiguration corsConfiguration = CORSConfiguration.builder().corsRules(s3CORSRuleList).build();

            // 将 CORS 配置应用到 S3 存储桶
            s3ClientNew.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(param.getBucketName())
                    .corsConfiguration(corsConfiguration)
                    .build());
        } catch (Exception e) {
            throw new CorsBizException("设置跨域失败, 请检查配置是否正确. 错误信息: " + e.getMessage(), e);
        }
    }


    /**
     * 上传文件到 S3 存储桶。
     * <p>
     * 根据文件扩展名自动推断 Content-Type，使用无超时限制的配置进行上传。
     * </p>
     *
     * @param pathAndName 文件完整路径及名称
     * @param inputStream 文件输入流
     * @param size        文件大小（字节）
     * @throws Exception 如果上传过程中发生异常
     */
    @Override
    public void uploadFile(String pathAndName, InputStream inputStream, Long size) throws Exception {
        // 根据文件扩展名推断 Content-Type
        String contentType = parseContentTypeByName(pathAndName, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        String trimStartPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), getCurrentUserBasePath(), pathAndName);

        // 使用无超时限制配置上传文件
        s3ClientNew.putObject(PutObjectRequest.builder()
                        .overrideConfiguration(unlimitTimeoutBuilderConsumer)
                        .bucket(param.getBucketName())
                        .key(trimStartPath)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(inputStream, size));
    }

    /**
     * 以流的形式代理下载文件（将文件内容直接写入 HTTP 响应输出流）。
     * <p>
     * 支持 HTTP Range 请求（断点续传/部分下载），使用无超时限制配置进行下载。
     * </p>
     * <p>
     * 路径契约：pathAndName 是相对 basePath 的绝对路径，不含 getCurrentUserBasePath 前缀。
     * </p>
     *
     * @param pathAndName 相对 basePath 的绝对路径
     * @return 始终返回 null（文件内容已直接写入响应流）
     * @throws Exception 如果下载过程中发生异常
     */
    @Override
    public ResponseEntity<org.springframework.core.io.Resource> downloadToStream(String pathAndName) throws Exception {
        String bucketName = param.getBucketName();
        String trimStartPath = StringUtils.concatTrimStartSlashes(param.getBasePath(), pathAndName);

        // 解析 HTTP Range 请求头，支持断点续传
        HttpRange requestRange = RequestUtils.getRequestRange(RequestHolder.getRequest());

        // 使用无超时限制配置下载文件
        ResponseInputStream<GetObjectResponse> responseResponseInputStream = s3ClientNew.getObject(GetObjectRequest.builder()
                .overrideConfiguration(unlimitTimeoutBuilderConsumer)
                .bucket(bucketName)
                .key(trimStartPath)
                .range(requestRange != null ? "bytes=" + requestRange.getRangeStart(Integer.MAX_VALUE) + "-" + requestRange.getRangeEnd(Integer.MAX_VALUE) : null)
                .build());

        long fileSize = Convert.toLong(responseResponseInputStream.response().contentLength());
        String fileName = FileUtils.getName(pathAndName);
        // 将文件内容直接写入当前 HTTP 响应流
        RequestHolder.writeFile(responseResponseInputStream, fileName, fileSize, true, param.isProxyLinkForceDownload());
        return null;
    }

    /**
     * 获取 S3 客户端的默认配置。
     * <p>
     * 设置 API 调用超时时间为默认的连接超时秒数。
     * 子类可以重写此方法以提供自定义的客户端配置。
     * </p>
     *
     * @return S3 客户端配置
     */
    public ClientOverrideConfiguration getClientConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_SECONDS)) // 设置 API 调用超时时间
                .build();
    }

    /**
     * 构造 S3 客户端的 ServiceConfiguration。
     * <p>
     * 默认启用分块编码（chunked encoding），子类如需附加自有选项
     * （如 pathStyleAccessEnabled 等），可重写后基于本方法返回值再行扩展。
     * </p>
     *
     * @return S3 服务配置
     */
    public S3Configuration getS3Configuration() {
        return S3Configuration.builder()
                .chunkedEncodingEnabled(param.isChunkedEncodingEnabled())
                .build();
    }

    /**
     * 获取存储源元数据信息。
     * <p>
     * S3 存储源不支持文件夹重命名/移动/复制以及非空文件夹删除等操作。
     * </p>
     *
     * @return 存储源元数据对象
     */
    @Override
    public StorageSourceMetadata getStorageSourceMetadata() {
        StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
        // 根据是否启用代理上传设置上传类型
        if (param.isEnableProxyUpload()) {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
        } else {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.S3);
        }
        // S3 存储源不支持的操作
        storageSourceMetadata.setSupportRenameFolder(false);
        storageSourceMetadata.setSupportMoveFolder(false);
        storageSourceMetadata.setSupportCopyFolder(false);
        storageSourceMetadata.setSupportDeleteNotEmptyFolder(false);
        storageSourceMetadata.setNeedCreateFolderBeforeUpload(false);
        return storageSourceMetadata;
    }

    /**
     * 根据文件名解析对应的 Content-Type（MIME 类型）。
     * <p>
     * 使用 {@link URLConnection#guessContentTypeFromName(String)} 根据文件扩展名推断，
     * 如果无法推断则返回默认值。
     * </p>
     *
     * @param pathAndName        文件路径及名称
     * @param defaultContentType 默认的 Content-Type（当无法推断时使用）
     * @return 文件对应的 Content-Type
     */
    private static String parseContentTypeByName(String pathAndName, String defaultContentType) {
        String contentType = URLConnection.guessContentTypeFromName(pathAndName);
        if (StringUtils.isBlank(contentType)) {
            contentType = defaultContentType;
        }
        return contentType;
    }

    /**
     * 销毁资源，在存储源关闭/清理时调用。
     * <p>
     * 关闭 S3 客户端、预签名器和下载预签名器，释放网络连接等资源。
     * </p>
     */
    @Override
    public void destroy() {
        if (this.s3ClientNew != null) {
            this.s3ClientNew.close();
        }
        if (this.s3Presigner != null) {
            this.s3Presigner.close();
        }
        if (this.s3PresignerDownload != null) {
            this.s3PresignerDownload.close();
        }
    }
}