package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.crypto.SecureUtil;
import com.UpYun;
import com.alibaba.fastjson2.JSON;
import com.upyun.Params;
import com.upyun.RestManager;
import com.upyun.UpException;
import com.upyun.UpYunUtils;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.constant.StorageSourceConnectionProperties;
import im.tlgxy.cloud.module.storage.model.bo.AuthModel;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.bo.UploadSignParam;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.UpYunParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 又拍云对象存储 (UpYun) 服务实现类。
 * <p>
 * 基于又拍云原生 Java SDK ({@link UpYun}) 和 REST Manager ({@link RestManager})，
 * 提供文件的上传、下载、列表、删除、重命名、复制、移动等操作。
 * 支持又拍云的签名认证、防盗链 Token、分页列表迭代等功能。
 * </p>
 * <p>
 * 上传方式支持两种模式：
 * <ul>
 *   <li>代理上传：通过 TlgxyCloud 服务器中转上传</li>
 *   <li>直传模式：生成又拍云上传签名，由客户端直接上传到又拍云</li>
 * </ul>
 * </p>
 * <p>
 * 由于又拍云上传 API 存在首次调用的并发问题，本类使用 {@link ReentrantLock}
 * 对第一次上传操作进行加锁保护。
 * </p>
 * <p>
 * 该 Bean 为原型作用域（PROTOTYPE），每次注入都会创建新实例，
 * 以便支持多个不同的存储桶配置同时存在。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Slf4j
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class UpYunServiceImpl extends AbstractProxyTransferService<UpYunParam> {

    /**
     * 又拍云删除非空目录时的错误消息关键词，用于判断是否因目录非空导致删除失败
     */
    private static final String DELETE_NO_EMPTY_FOLDERS_MESSAGE = "directory not empty";

    /**
     * 又拍云文件列表迭代结束标记，当 nextMark 等于该值时表示所有文件已迭代完毕
     */
    private static final String END_MARK = "g2gCZAAEbmV4dGQAA2VvZg";

    /**
     * 又拍云原生 SDK 客户端，用于文件管理操作
     */
    private UpYun upYun;

    /**
     * 又拍云 REST API 管理器，用于文件读取等 REST 接口调用
     */
    private RestManager restManager;

    /**
     * 标记是否为首次上传。
     * 使用 volatile 关键字确保多线程间的可见性。
     */
    private static volatile boolean isFirstUpload = true;

    /**
     * 可重入锁，用于保护首次上传操作，防止又拍云 API 的并发异常
     */
    private static final Lock lock = new ReentrantLock();

    /**
     * 初始化又拍云存储服务。
     * <p>
     * 创建 RestManager 和 UpYun 客户端，配置连接超时时间。
     * </p>
     */
    @Override
    public void init() {
        // 创建 REST API 管理器，用于文件读取等操作
        restManager = new RestManager(param.getBucketName(), param.getUsername(), param.getPassword());
        restManager.setTimeout(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_SECONDS);
        // 创建又拍云原生 SDK 客户端，用于文件管理操作
        upYun = new UpYun(param.getBucketName(), param.getUsername(), param.getPassword());
        upYun.setTimeout(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_SECONDS);
    }

    /**
     * 获取指定目录下的文件列表。
     * <p>
     * 使用又拍云的分页迭代 API，每次获取最多 100 条记录，通过 nextMark
     * 控制翻页，直到所有文件迭代完毕。
     * </p>
     *
     * @param folderPath 要列出的目录路径
     * @return 文件/目录结果列表
     * @throws Exception 如果又拍云 API 调用过程中发生错误
     */
    @Override
    public List<FileItemResult> fileList(String folderPath) throws Exception {
        ArrayList<FileItemResult> fileItemList = new ArrayList<>();
        // 分页迭代标记，首次请求为 null
        String nextMark = null;

        do {
            // 构建请求参数：迭代标记和每页大小（最多100条）
            HashMap<String, String> hashMap = new HashMap<>(24);
            hashMap.put("x-list-iter", nextMark);
            hashMap.put("x-list-limit", "100");
            // 调用又拍云 API 获取一页目录数据
            UpYun.FolderItemIter folderItemIter = upYun.readDirIter(StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), folderPath), hashMap);
            // 获取下一次迭代的标记
            nextMark = folderItemIter.iter;
            ArrayList<UpYun.FolderItem> folderItems = folderItemIter.files;
            if (folderItems != null) {
                for (UpYun.FolderItem folderItem : folderItems) {
                    FileItemResult fileItemResult = new FileItemResult();
                    fileItemResult.setName(folderItem.name);
                    fileItemResult.setSize(folderItem.size);
                    fileItemResult.setTime(folderItem.date);
                    fileItemResult.setPath(folderPath);
                    // 根据又拍云返回的 type 字段判断是文件夹还是文件
                    fileItemResult.setType("folder".equals(folderItem.type) ? FileTypeEnum.FOLDER : FileTypeEnum.FILE);
                    // 如果是文件，生成下载 URL
                    if (fileItemResult.getType() == FileTypeEnum.FILE) {
                        String pathAndName = StringUtils.concat(getCurrentUserBasePath(), folderPath, fileItemResult.getName());
                        fileItemResult.setUrl(getDownloadUrl(pathAndName));
                    }
                    fileItemList.add(fileItemResult);
                }
            }
        } while (!END_MARK.equals(nextMark)); // 当迭代标记为结束标记时，停止循环
        return fileItemList;
    }

    /**
     * 获取文件下载 URL。
     * <p>
     * 如果启用了代理下载且未配置自定义域名，则返回代理下载链接；
     * 否则返回又拍云原始下载链接（支持防盗链 Token）。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件下载 URL
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getDomain())) {
            // 使用代理下载
            return super.getProxyDownloadUrl(pathAndName);
        } else {
            // 使用又拍云原始下载链接
            return getOriginDownloadUrl(pathAndName);
        }
    }

    /**
     * 获取又拍云原始下载链接。
     * <p>
     * 构建又拍云直链下载地址，如果配置了防盗链 Token，
     * 则在 URL 后拼接 _upt 参数实现防盗链验证。
     * 防盗链 Token 的计算方式为：
     * MD5(token + "&" + 过期时间戳 + "&" + 文件路径) 取第12-20位字符。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 又拍云原始下载 URL
     */
    public String getOriginDownloadUrl(String pathAndName) {
        String fullPath = StringUtils.concat(param.getBasePath(), pathAndName);

        // 确定下载域名：优先使用自定义域名，否则使用又拍云默认测试域名
        String domain = StringUtils.isBlank(param.getDomain()) ? "http://" + param.getBucketName() + ".test.upcdn.net" : param.getDomain();

        // 拼接域名和编码后的文件路径
        String baseDownloadUrl = StringUtils.concat(domain, StringUtils.encodeAllIgnoreSlashes(fullPath));
        // 判断是否配置了 token 防盗链
        if (StringUtils.isNotEmpty(param.getToken())) {
            // 如果前面没有补 /, 则自动补 /, 不然生成的防盗链是无效的
            long tokenTime = param.getProxyTokenTime();
            // 计算防盗链的过期时间戳（秒）
            long etime = System.currentTimeMillis() / 1000 + TimeUnit.MINUTES.toSeconds(tokenTime);
            // 计算防盗链 Token：MD5(token + "&" + etime + "&" + fullPath)，取第12-20位
            String downloadToken = SecureUtil.md5(param.getToken() + "&" + etime + "&" + fullPath).substring(12, 20);
            // 拼接防盗链参数
            baseDownloadUrl += "?_upt=" + downloadToken + etime;
        }

        return baseDownloadUrl;
    }

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#UPYUN}，表示又拍云存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.UPYUN;
    }

    /**
     * 根据文件路径和名称获取单个文件信息。
     * <p>
     * 通过又拍云原生 SDK 获取文件的元数据信息，
     * 包括文件大小、修改时间、文件类型等。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件信息对象，如果文件不存在则返回 {@code null}
     */
    @Override
    public FileItemResult getFileItem(String pathAndName) {
        // 构建 URL 编码后的完整文件路径
        String encodeFullUrl = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), pathAndName);
        Map<String, String> fileInfo;
        try {
            // 调用又拍云 API 获取文件元数据
            fileInfo = upYun.getFileInfo(encodeFullUrl);
        } catch (IOException | UpException e) {
            throw ExceptionUtil.wrapRuntime(e);
        }

        // 如果文件信息为空，表示文件不存在
        if (fileInfo == null) {
            return null;
        }

        String name = FileUtils.getName(pathAndName);
        String folderPath = FileUtils.getParentPath(pathAndName);
        FileItemResult fileItemResult = new FileItemResult();
        fileItemResult.setName(name);
        // 从又拍云返回的头信息中提取文件大小
        fileItemResult.setSize(Long.valueOf(fileInfo.get("x-upyun-file-size")));
        // 从又拍云返回的头信息中提取文件修改时间（Unix 时间戳，秒 → 毫秒）
        fileItemResult.setTime(new Date(Long.parseLong(fileInfo.get("x-upyun-file-date")) * 1000));
        fileItemResult.setPath(folderPath);

        // 根据文件类型设置对应的枚举值和下载 URL
        if ("folder".equals(fileInfo.get("x-upyun-file-type"))) {
            fileItemResult.setType(FileTypeEnum.FOLDER);
        } else {
            fileItemResult.setType(FileTypeEnum.FILE);
            fileItemResult.setUrl(getDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), pathAndName)));
        }
        return fileItemResult;
    }

    /**
     * 创建新文件夹。
     * <p>
     * 通过又拍云 SDK 创建目录，支持递归创建父级目录。
     * </p>
     *
     * @param path 父目录路径
     * @param name 新文件夹名称
     * @return 创建成功返回 {@code true}
     */
    @Override
    public boolean newFolder(String path, String name) {
        // 拼接完整目录路径
        String fullPath = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), path, name);
        try {
            // 创建目录，第二个参数 true 表示递归创建父级目录
            return upYun.mkDir(fullPath, true);
        } catch (IOException | UpException e) {
            throw ExceptionUtil.wrapRuntime(e);
        }
    }

    /**
     * 删除指定文件。
     * <p>
     * 如果删除的"文件"实际上是一个非空目录，又拍云会返回 "directory not empty" 错误，
     * 此时会抛出业务异常提示用户目录非空。
     * </p>
     *
     * @param path 文件所在目录路径
     * @param name 要删除的文件名称
     * @return 删除成功返回 {@code true}
     * @throws BizException 如果尝试删除非空目录
     */
    @Override
    public boolean deleteFile(String path, String name) {
        String fullPath = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), path, name);
        try {
            return upYun.deleteFile(fullPath, null);
        } catch (IOException | UpException e) {
            // 检查是否为"目录非空"错误
            if (e instanceof UpException) {
                String message = e.getMessage();
                if (StringUtils.contains(message, DELETE_NO_EMPTY_FOLDERS_MESSAGE)) {
                    throw new BizException(ErrorCode.BIZ_DELETE_FILE_NOT_EMPTY);
                }
            }
            throw ExceptionUtil.wrapRuntime(e);
        }
    }

    /**
     * 删除指定文件夹。
     * <p>
     * 又拍云中文件和文件夹的删除操作使用相同的 API，直接复用 deleteFile 方法。
     * </p>
     *
     * @param path 父目录路径
     * @param name 要删除的文件夹名称
     * @return 删除成功返回 {@code true}
     */
    @Override
    public boolean deleteFolder(String path, String name) {
        return deleteFile(path, name);
    }

    /**
     * 重命名文件。
     * <p>
     * 通过又拍云 SDK 的 moveFile 方法实现文件重命名。
     * 注意：又拍云的 moveFile(dst, src) 参数顺序是目标路径在前，源路径在后。
     * </p>
     *
     * @param path    文件所在目录路径
     * @param name    文件当前名称
     * @param newName 文件新名称
     * @return 重命名成功返回 {@code true}
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        // 拼接源路径（包含 bucketName）
        String srcPath = StringUtils.concat(true, param.getBucketName(), param.getBasePath(), getCurrentUserBasePath(), path, name);
        // 拼接目标路径
        String distPath = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), path, newName);

        try {
            // 又拍云 moveFile 参数：目标路径在前，源路径在后
            return upYun.moveFile(distPath, srcPath);
        } catch (IOException | UpException e) {
            throw ExceptionUtil.wrapRuntime(e);
        }
    }

    /**
     * 重命名文件夹。
     * <p>
     * 又拍云不支持重命名文件夹操作，因此该方法始终抛出异常。
     * </p>
     *
     * @param path    文件夹所在目录路径
     * @param name    文件夹当前名称
     * @param newName 文件夹新名称
     * @throws BizException 操作不支持异常
     */
    @Override
    public boolean renameFolder(String path, String name, String newName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 获取文件上传 URL（或签名信息）。
     * <p>
     * 根据配置决定上传方式：
     * <ul>
     *   <li>如果启用了代理上传，则返回代理上传端点 URL</li>
     *   <li>否则生成又拍云上传签名信息，由客户端直传到又拍云</li>
     * </ul>
     * </p>
     *
     * @param path 上传目标目录路径
     * @param name 上传文件名
     * @param size 上传文件大小
     * @return 代理上传 URL（JSON字符串）或又拍云签名信息（JSON字符串）
     */
    @Override
    public String getUploadUrl(String path, String name, Long size) {
        // 如果启用了代理上传，使用代理方式
        if (param.isEnableProxyUpload()) {
            return super.getProxyUploadUrl(path, name);
        }
        // 构建上传签名参数，用于客户端直传
        UploadSignParam uploadSignParam = new UploadSignParam();
        uploadSignParam.setPath(StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path));
        uploadSignParam.setSize(size);
        uploadSignParam.setName(name);
        // 生成又拍云上传认证信息
        AuthModel authModel = generatorAuthModel(uploadSignParam);
        return JSON.toJSONString(authModel);
    }

    /**
     * 复制文件。
     * <p>
     * 通过又拍云 SDK 的 copyFile 方法实现文件复制。
     * </p>
     *
     * @param path       源文件所在目录路径
     * @param name       源文件名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件名称
     * @return 复制成功返回 {@code true}
     */
    @Override
    public boolean copyFile(String path, String name, String targetPath, String targetName) {
        // 拼接源文件路径（包含 bucketName）
        String srcPath = StringUtils.concat(true, param.getBucketName(), param.getBasePath(), getCurrentUserBasePath(), path, name);
        // 拼接目标文件路径
        String distPath = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), targetPath, targetName);

        try {
            return upYun.copyFile(distPath, srcPath);
        } catch (IOException | UpException e) {
            throw ExceptionUtil.wrapRuntime(e);
        }
    }

    /**
     * 复制文件夹。
     * <p>
     * 又拍云不支持直接复制文件夹操作，因此该方法始终抛出异常。
     * </p>
     *
     * @param path       源文件夹所在目录路径
     * @param name       源文件夹名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件夹名称
     * @throws BizException 操作不支持异常
     */
    @Override
    public boolean copyFolder(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 移动文件。
     * <p>
     * 通过又拍云 SDK 的 moveFile 方法实现文件移动。
     * </p>
     *
     * @param path       源文件所在目录路径
     * @param name       源文件名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件名称
     * @return 移动成功返回 {@code true}
     */
    @Override
    public boolean moveFile(String path, String name, String targetPath, String targetName) {
        // 拼接源文件路径（包含 bucketName）
        String srcPath = StringUtils.concat(true, param.getBucketName(), param.getBasePath(), getCurrentUserBasePath(), path, name);
        // 拼接目标文件路径
        String distPath = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), targetPath, targetName);

        try {
            // 又拍云 moveFile 参数：目标路径在前，源路径在后
            return upYun.moveFile(distPath, srcPath);
        } catch (IOException | UpException e) {
            throw ExceptionUtil.wrapRuntime(e);
        }
    }

    /**
     * 移动文件夹。
     * <p>
     * 又拍云不支持直接移动文件夹操作，因此该方法始终抛出异常。
     * </p>
     *
     * @param path       源文件夹所在目录路径
     * @param name       源文件夹名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件夹名称
     * @throws BizException 操作不支持异常
     */
    @Override
    public boolean moveFolder(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
    }

    /**
     * 上传会话过期时间（秒），默认 1800 秒即 30 分钟
     */
    private static final int UPLOAD_SESSION_EXPIRATION = 1800;

    /**
     * 计算又拍云 API 请求签名。
     * <p>
     * 签名算法：对 "METHOD&URI"（如果存在 policy 则为 "METHOD&URI&POLICY"）
     * 使用 HMAC-SHA1 加密，密钥为操作员的密码 MD5 值，
     * 最后将结果进行 Base64 编码。
     * </p>
     *
     * @param key    操作员用户名
     * @param secret 操作员密码的 MD5 值
     * @param method HTTP 请求方法（如 POST、GET）
     * @param uri    请求的 URI 路径
     * @param policy 上传策略（可选，用于上传签名）
     * @return 又拍云格式的签名字符串（格式：UPYUN key:signature）
     */
    private String sign(String key, String secret, String method, String uri, String policy) {
        // 构建待签名的字符串：METHOD&URI
        String value = method + "&" + uri;
        // 如果存在上传策略，追加到待签名字符串
        if (StringUtils.isNotEmpty(policy)) {
            value = value + "&" + policy;
        }
        // 使用 HMAC-SHA1 算法计算签名
        byte[] hmac = SecureUtil.hmacSha1(secret).digest(value);
        // Base64 编码签名结果
        String sign = Base64.getEncoder().encodeToString(hmac);
        return "UPYUN " + key + ":" + sign;
    }

    /**
     * 生成又拍云上传认证模型（包含上传 URL、签名和策略）。
     * <p>
     * 用于客户端直传模式，生成包含上传端点、签名和上传策略的认证信息，
     * 客户端凭此信息可直接将文件上传到又拍云。
     * </p>
     *
     * @param uploadSignParam 上传签名参数（包含路径、文件名、文件大小）
     * @return 上传认证模型，包含上传 URL、签名字符串和上传策略
     */
    public AuthModel generatorAuthModel(UploadSignParam uploadSignParam) {
        // 获取上传策略（包含空间名、保存路径、过期时间、文件大小限制）
        String policy = getPolicy(uploadSignParam);

        String method = "POST";
        String uri = StringUtils.SLASH + param.getBucketName();

        // 上传，处理，内容识别有存储
        // 使用操作员用户名和密码（MD5 加密）计算签名
        String signature = sign(param.getUsername(), SecureUtil.md5(param.getPassword()), method, uri, policy);

        // 返回包含上传端点 URL、签名和策略的认证模型
        return new AuthModel("https://v0.api.upyun.com/" + param.getBucketName(), signature, policy);
    }

    /**
     * 获取上传 policy（上传策略）。
     * <p>
     * 生成又拍云上传所需的策略字符串，包含以下信息：
     * <ul>
     *   <li>空间名称（bucket）</li>
     *   <li>文件保存路径（save-key）</li>
     *   <li>策略过期时间（expiration）</li>
     *   <li>文件大小限制（content-length-range）</li>
     * </ul>
     * </p>
     *
     * @param uploadSignParam 上传签名参数
     * @return 上传策略字符串
     */
    private String getPolicy(UploadSignParam uploadSignParam) {
        String bucketName = param.getBucketName();
        HashMap<String, Object> params = new HashMap<>();
        // 设置空间名称
        params.put(Params.BUCKET, bucketName);
        // 设置文件保存的完整路径
        params.put(Params.SAVE_KEY, StringUtils.concat(uploadSignParam.getPath(), uploadSignParam.getName()));
        // 设置策略过期时间为当前时间 + 1800 秒
        params.put(Params.EXPIRATION, System.currentTimeMillis() / 1000 + UPLOAD_SESSION_EXPIRATION);
        // 设置上传文件的精确大小
        params.put("content-length", uploadSignParam.getSize());
        // 设置允许上传的文件大小范围
        params.put(Params.CONTENT_LENGTH_RANGE, "0," + uploadSignParam.getSize());
        // 使用又拍云 SDK 工具类将参数转换为策略字符串
        return UpYunUtils.getPolicy(params);
    }

    /**
     * 上传文件。
     * <p>
     * 由于又拍云上传 API 在并发调用时可能抛出异常，因此对首次上传进行加锁保护。
     * 第一次上传完成后，后续上传不再需要加锁。
     * </p>
     *
     * @param pathAndName 上传目标路径和文件名
     * @param inputStream 文件内容的输入流
     * @param size        文件大小
     * @throws IOException 如果 IO 操作失败
     * @throws UpException 如果又拍云 API 调用失败
     */
    @Override
    public void uploadFile(String pathAndName, InputStream inputStream, Long size) throws IOException, UpException {
        boolean doLock = isFirstUpload;

        if (doLock) {
            // 在第一次上传时加锁，防止并发异常
            lock.lock();
            try {
                // 双重检查：再次确认 isFirstUpload 没有变更
                if (isFirstUpload) {
                    tryUpload(pathAndName, inputStream);
                    // 第一次上传成功后修改标志，后续上传不再加锁
                    isFirstUpload = false;
                }
            } finally {
                // 释放锁
                lock.unlock();
            }
        } else {
            // 对于后续的上传，直接处理，无需锁
            tryUpload(pathAndName, inputStream);
        }
    }

    /**
     * 执行实际的文件上传操作。
     *
     * @param pathAndName 上传目标路径和文件名
     * @param inputStream 文件内容的输入流
     * @throws IOException 如果 IO 操作失败
     * @throws UpException 如果又拍云 API 返回失败
     */
    private void tryUpload(String pathAndName, InputStream inputStream) throws IOException, UpException {
        // 构建 URL 编码后的完整上传路径
        String encodeFullUrl = StringUtils.concat(true, param.getBasePath(), getCurrentUserBasePath(), pathAndName);
        // 调用又拍云 SDK 写入文件，第三个参数 true 表示自动创建父级目录
        boolean isSuccess = upYun.writeFile(encodeFullUrl, inputStream, true, null);
        if (!isSuccess) {
            log.error("又拍云上传失败，pathAndName：{}", pathAndName);
            // 抛出异常，便于上层处理错误
            throw new UpException("上传失败");
        }
    }

    /**
     * 下载文件到输出流（代理下载方式）。
     * <p>
     * 通过又拍云 REST Manager 读取文件内容，并写入 HTTP 响应输出流。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 包含文件资源的 ResponseEntity，写入后返回 null 由框架处理
     * @throws Exception 如果文件下载过程中发生错误
     */
    @Override
    public ResponseEntity<Resource> downloadToStream(String pathAndName) throws Exception {
        // 拼接完整文件路径
        String fullUrl = StringUtils.concat(param.getBasePath(), pathAndName);
        // 通过 REST Manager 读取文件
        Response response = restManager.readFile(fullUrl);
        // 获取文件内容的输入流
        InputStream inputStream = response.body().byteStream();
        String fileName = FileUtils.getName(pathAndName);
        // 从响应头中获取文件大小
        long fileSize = Convert.toLong(response.header(HttpHeaders.CONTENT_LENGTH, "0"));
        // 将文件内容写入 HTTP 响应输出流
        RequestHolder.writeFile(inputStream, fileName, fileSize, false, param.isProxyLinkForceDownload());
        return null;
    }

    /**
     * 获取存储源元数据信息。
     * <p>
     * 返回又拍云存储源的功能元数据，包括：
     * <ul>
     *   <li>上传类型：代理上传或又拍云直传</li>
     *   <li>不支持的操作：文件夹重命名、文件夹移动、文件夹复制</li>
     *   <li>不支持删除非空目录</li>
     *   <li>上传前不需要预先创建目录</li>
     * </ul>
     * </p>
     *
     * @return 存储源元数据对象
     */
    @Override
    public StorageSourceMetadata getStorageSourceMetadata() {
        StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
        // 根据配置决定上传类型：代理上传或又拍云直传
        if (param.isEnableProxyUpload()) {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
        } else {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.UPYUN);
        }
        // 又拍云不支持以下操作
        storageSourceMetadata.setSupportRenameFolder(false);
        storageSourceMetadata.setSupportMoveFolder(false);
        storageSourceMetadata.setSupportCopyFolder(false);
        storageSourceMetadata.setSupportDeleteNotEmptyFolder(false);
        storageSourceMetadata.setNeedCreateFolderBeforeUpload(false);
        return storageSourceMetadata;
    }
}