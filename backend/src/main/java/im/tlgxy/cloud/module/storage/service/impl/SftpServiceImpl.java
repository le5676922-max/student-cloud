package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.extra.ssh.Sftp;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.core.util.*;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.SftpParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.storage.support.ftp.FtpClientFactory;
import im.tlgxy.cloud.module.storage.support.sftp.SFtpClientFactory;
import im.tlgxy.cloud.module.storage.support.sftp.SFtpClientPool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpRange;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * SFTP 文件存储服务实现类。
 * <p>
 * 基于 JSch (Java Secure Channel) 客户端的 Hutool 封装 {@link Sftp}，
 * 通过 SFTP 协议连接远程服务器，提供文件的上传、下载、列表、删除、
 * 重命名、移动等操作。
 * </p>
 * <p>
 * 使用 Apache Commons Pool2 对象池 ({@link SFtpClientPool}) 管理 SFTP 连接，
 * 避免频繁创建和销毁连接的开销，支持连接复用和资源回收。
 * </p>
 * <p>
 * 文件传输采用代理中转方式，不支持文件复制操作，
 * 继承自 {@link AbstractProxyTransferService}。
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
public class SftpServiceImpl extends AbstractProxyTransferService<SftpParam> {

    /**
     * SFTP 客户端对象池，用于管理和复用 SFTP 连接
     */
    private SFtpClientPool sftpClientPool;

    /**
     * 初始化 SFTP 存储服务。
     * <p>
     * 根据配置参数创建 SFTP 连接工厂和连接池，配置连接池的测试、
     * 最大连接数、空闲回收时间和最大等待时间等参数。
     * </p>
     */
    @Override
    public void init() {
        // 根据配置的编码创建 Charset 对象
        Charset charset = Charset.forName(param.getEncoding());
        // 创建 SFTP 客户端工厂，传入主机、端口、用户名、密码/密钥等连接参数
        SFtpClientFactory factory = new SFtpClientFactory(param.getHost(), param.getPort(), param.getUsername(), param.getPassword(), param.getPrivateKey(), param.getPassphrase(), charset, param.getHostKeyFingerprint());
        // 配置连接池参数
        GenericObjectPoolConfig<FtpClientFactory> config = new GenericObjectPoolConfig<>();
        // 从连接池借用对象时，先进行有效性测试
        config.setTestOnBorrow(true);
        // 设置连接池最大连接数
        config.setMaxTotal(param.getMaxConnections());
        // 2 分钟没有使用则进行回收
        config.setMinEvictableIdleDuration(Duration.ofMinutes(2));
        // 等待获取连接的最大超时时间为 15 秒
        config.setMaxWait(Duration.ofSeconds(15));
        // 创建 SFTP 客户端连接池
        sftpClientPool = new SFtpClientPool(factory, config);
    }

    /**
     * 从连接池中获取一个 SFTP 客户端。
     *
     * @return SFTP 客户端对象
     * @throws BizException    如果连接池已满，无法获取新的连接
     * @throws SystemException 如果获取连接时发生其他异常
     */
    public Sftp getClientFromPool() {
        try {
            return sftpClientPool.borrowObject();
        } catch (NoSuchElementException e) {
            // 连接池已满，抛出业务异常
            throw new BizException(ErrorCode.BIZ_SFTP_CLIENT_POOL_FULL);
        } catch (Exception e) {
            // 其他异常包装为系统异常抛出
            throw new SystemException(e);
        }
    }

    /**
     * 获取指定目录下的文件列表。
     * <p>
     * 从连接池获取 SFTP 连接，列出指定路径下的所有文件和目录，
     * 并将其转换为 {@link FileItemResult} 列表返回。
     * </p>
     *
     * @param folderPath 要列出的目录路径
     * @return 文件/目录结果列表
     * @throws Exception 如果 SFTP 操作过程中发生错误
     */
    @Override
    public List<FileItemResult> fileList(String folderPath) throws Exception {
        List<FileItemResult> result = new ArrayList<>();

        Sftp sftp = null;
        try {
            // 从连接池获取 SFTP 客户端
            sftp = getClientFromPool();
            // 拼接完整路径：基础路径 + 用户基础路径 + 目录路径
            String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), folderPath);
            // 列出目录下的所有条目
            List<ChannelSftp.LsEntry> entryList = sftp.lsEntries(fullPath);
            // 将每个 SFTP 条目转换为 FileItemResult 对象
            for (ChannelSftp.LsEntry sftpEntry : entryList) {
                FileItemResult fileItemResult = sftpEntryToFileItem(sftpEntry, folderPath);
                result.add(fileItemResult);
            }
            return result;
        } finally {
            // 确保将连接归还到连接池
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#SFTP}，表示 SFTP 存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.SFTP;
    }


    /**
     * 根据文件路径和名称获取单个文件信息。
     * <p>
     * 通过 SFTP 协议获取指定路径下的文件详细信息。
     * </p>
     *
     * @param pathAndName         文件的路径和名称
     * @param containUserBasePath 路径中是否已包含用户基础路径
     * @return 文件信息对象，如果文件不存在则返回 {@code null}
     */
    public FileItemResult getFileItem(String pathAndName, boolean containUserBasePath) {
        // 拼接完整文件路径，根据参数决定是否添加用户基础路径
        String fullPath = StringUtils.concat(param.getBasePath(), containUserBasePath ? getCurrentUserBasePath() : "", pathAndName);

        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            List<ChannelSftp.LsEntry> entryList = sftp.lsEntries(fullPath);

            // 如果列出的条目列表为空，返回 null
            if (CollectionUtils.isEmpty(entryList)) {
                return null;
            }

            // 获取第一个条目（通常单个文件查询只返回一个结果）
            ChannelSftp.LsEntry sftpEntry = CollectionUtils.getFirst(entryList);
            if (sftpEntry == null) {
                return null;
            }
            // 提取父目录路径，用于设置文件信息中的路径字段
            String folderName = FileUtils.getParentPath(pathAndName);
            return sftpEntryToFileItem(sftpEntry, folderName);
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }

    /**
     * 根据文件路径和名称获取单个文件信息（默认包含用户基础路径）。
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件信息对象，如果文件不存在则返回 {@code null}
     */
    @Override
    public FileItemResult getFileItem(String pathAndName) {
        return getFileItem(pathAndName, true);
    }

    /**
     * 创建新文件夹。
     *
     * @param path 父目录路径
     * @param name 新文件夹名称
     * @return 创建成功返回 {@code true}
     */
    @Override
    public boolean newFolder(String path, String name) {
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 拼接完整路径后创建目录
            sftp.mkdir(StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name));
            return true;
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 删除指定文件。
     *
     * @param path 文件所在目录路径
     * @param name 要删除的文件名称
     * @return 删除成功返回 {@code true}
     */
    @Override
    public boolean deleteFile(String path, String name) {
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 拼接完整文件路径后执行删除
            return sftp.delFile(StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name));
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 删除指定文件夹。
     *
     * @param path 父目录路径
     * @param name 要删除的文件夹名称
     * @return 删除成功返回 {@code true}
     */
    @Override
    public boolean deleteFolder(String path, String name) {
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 拼接完整文件夹路径后执行删除
            return sftp.delDir(StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name));
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }

    /**
     * 复制文件。
     * <p>
     * SFTP 协议不支持直接复制文件操作，因此该方法始终抛出异常。
     * </p>
     *
     * @param path       源文件所在目录路径
     * @param name       源文件名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件名称
     * @throws BizException 操作不支持异常
     */
    @Override
    public boolean copyFile(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_UNSUPPORTED_OPERATION);
    }

    /**
     * 复制文件夹。
     * <p>
     * SFTP 协议不支持直接复制文件夹操作，因此该方法始终抛出异常。
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
        throw new BizException(ErrorCode.BIZ_UNSUPPORTED_OPERATION);
    }

    /**
     * 移动文件。
     * <p>
     * 通过 SFTP 的 rename 方法实现文件从一个路径到另一个路径的移动。
     * </p>
     *
     * @param path       源文件所在目录路径
     * @param name       源文件名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件名称
     * @return 移动成功返回 {@code true}
     * @throws SystemException 如果 SFTP 操作失败
     */
    @Override
    public boolean moveFile(String path, String name, String targetPath, String targetName) {
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 拼接源文件和目标文件的完整路径
            String srcPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
            String distPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), targetPath, targetName);
            // 使用 SFTP rename 实现文件移动
            sftp.getClient().rename(srcPath, distPath);
            return true;
        } catch (SftpException e) {
            throw new SystemException(e);
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }

    /**
     * 移动文件夹。
     * <p>
     * SFTP 协议中文件夹移动与文件移动使用相同的 rename 操作。
     * </p>
     *
     * @param path       源文件夹所在目录路径
     * @param name       源文件夹名称
     * @param targetPath 目标目录路径
     * @param targetName 目标文件夹名称
     * @return 移动成功返回 {@code true}
     */
    @Override
    public boolean moveFolder(String path, String name, String targetPath, String targetName) {
        // 文件夹移动与文件移动在 SFTP 中使用相同的 rename 操作
        return moveFile(path, name, targetPath, targetName);
    }


    /**
     * 重命名文件。
     * <p>
     * 通过 SFTP 的 rename 方法实现文件重命名。
     * </p>
     *
     * @param path    文件所在目录路径
     * @param name    文件当前名称
     * @param newName 文件新名称
     * @return 重命名成功返回 {@code true}
     * @throws SystemException 如果 SFTP 操作失败
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 拼接源文件名和目标文件名的完整路径
            String srcPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
            String distPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, newName);
            // 使用 SFTP rename 实现重命名
            sftp.getClient().rename(srcPath, distPath);
            return true;
        } catch (SftpException e) {
            throw new SystemException(e);
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 重命名文件夹。
     * <p>
     * SFTP 协议中文件夹重命名与文件重命名使用相同的 rename 操作。
     * </p>
     *
     * @param path    文件夹所在目录路径
     * @param name    文件夹当前名称
     * @param newName 文件夹新名称
     * @return 重命名成功返回 {@code true}
     */
    @Override
    public boolean renameFolder(String path, String name, String newName) {
        // 文件夹重命名与文件重命名在 SFTP 中使用相同的 rename 操作
        return renameFile(path, name, newName);
    }


    /**
     * 下载文件到输出流（代理下载方式）。
     * <p>
     * 支持断点续传（Range 请求头），通过 SFTP 协议读取文件的指定偏移位置开始传输。
     * 如果配置了自定义域名，则表示期望从公网直接下载，此时不应使用代理下载，
     * 会抛出异常提示。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 包含文件资源的 ResponseEntity，写入后返回 null 由框架处理
     * @throws Exception 如果文件不存在或 SFTP 操作过程中发生错误
     */
    @Override
    public ResponseEntity<Resource> downloadToStream(String pathAndName) throws Exception {
        // 如果配置了域名，还访问代理下载 URL, 则抛出异常进行提示
        if (StringUtils.isNotEmpty(param.getDomain())) {
            throw new BizException(ErrorCode.BIZ_UNSUPPORTED_PROXY_DOWNLOAD);
        }

        // 获取文件信息，不包含用户基础路径（因为 pathAndName 已拼接完整）
        FileItemResult fileItem = getFileItem(pathAndName, false);
        if (fileItem == null) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }

        long fileSize = fileItem.getSize();
        // 重新拼接包含基础路径的完整路径
        pathAndName = StringUtils.concat(param.getBasePath(), pathAndName);
        String fileName = FileUtils.getName(pathAndName);

        // 根据请求头中的 Range 参数, 获取要跳过的字节数（支持断点续传）
        long skip = 0;
        HttpRange requestRange = RequestUtils.getRequestRange(RequestHolder.getRequest());
        if (requestRange != null) {
            skip = (int) requestRange.getRangeStart(fileSize);
        }

        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 通过 SFTP 获取文件输入流，从 skip 位置开始读取
            InputStream inputStream = sftp.getClient().get(pathAndName, null, skip);
            // 将文件内容写入 HTTP 响应输出流
            RequestHolder.writeFile(inputStream, fileName, fileSize, true, param.isProxyLinkForceDownload());
            return null;
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 获取文件上传 URL。
     * <p>
     * SFTP 文件上传采用代理上传方式，返回代理上传端点 URL。
     * </p>
     *
     * @param path 上传目标目录路径
     * @param name 上传文件名
     * @param size 上传文件大小
     * @return 代理上传 URL
     */
    @Override
    public String getUploadUrl(String path, String name, Long size) {
        return super.getProxyUploadUrl(path, name);
    }


    /**
     * 获取文件下载 URL。
     * <p>
     * 如果配置了自定义域名，则返回基于域名的直链下载地址；
     * 否则返回代理下载地址。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件下载 URL
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        // 如果配置了自定义域名，使用域名直链
        if (StringUtils.isNotBlank(param.getDomain())) {
            return StringUtils.concat(param.getDomain(), StringUtils.encodeAllIgnoreSlashes(pathAndName));
        }
        // 否则使用代理下载
        return super.getProxyDownloadUrl(pathAndName);
    }

    /**
     * 上传文件。
     * <p>
     * 通过 SFTP 协议将输入流中的文件内容上传到指定路径。
     * </p>
     *
     * @param pathAndName 上传目标路径和文件名
     * @param inputStream 文件内容的输入流
     * @param size        文件大小
     */
    @Override
    public void uploadFile(String pathAndName, InputStream inputStream, Long size) {
        // 拼接完整上传路径
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), pathAndName);
        String fileName = FileUtils.getName(pathAndName);
        // 提取目标文件夹路径
        String folderName = FileUtils.getParentPath(fullPath);
        Sftp sftp = null;
        try {
            sftp = getClientFromPool();
            // 执行 SFTP 上传
            sftp.upload(folderName, fileName, inputStream);
        } finally {
            if (sftp != null) {
                sftpClientPool.returnObject(sftp);
            }
        }
    }


    /**
     * 将 SFTP 目录条目转换为文件信息结果对象。
     * <p>
     * 从 {@link ChannelSftp.LsEntry} 中提取文件名、修改时间、文件大小、
     * 文件类型等信息，并设置对应的下载 URL。
     * </p>
     *
     * @param sftpEntry  SFTP 目录条目
     * @param folderPath 目录路径
     * @return 转换后的文件信息结果对象
     */
    public FileItemResult sftpEntryToFileItem(ChannelSftp.LsEntry sftpEntry, String folderPath) {
        FileItemResult fileItemResult = new FileItemResult();
        // 设置文件名
        fileItemResult.setName(sftpEntry.getFilename());
        // 设置修改时间（SFTP 返回的是 Unix 时间戳，单位秒，转换为毫秒）
        fileItemResult.setTime(DateUtil.date(sftpEntry.getAttrs().getMTime() * 1000L));
        // 根据是否为目录设置文件大小（目录大小为 null）
        fileItemResult.setSize(sftpEntry.getAttrs().isDir() ? null : sftpEntry.getAttrs().getSize());
        // 设置文件类型：目录或文件
        fileItemResult.setType(sftpEntry.getAttrs().isDir() ? FileTypeEnum.FOLDER : FileTypeEnum.FILE);
        // 设置所在目录路径
        fileItemResult.setPath(folderPath);
        // 如果是文件，生成下载 URL
        if (fileItemResult.getType() == FileTypeEnum.FILE) {
            fileItemResult.setUrl(getDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), folderPath, fileItemResult.getName())));
        }
        return fileItemResult;
    }

    /**
     * 获取存储源元数据信息。
     * <p>
     * 返回上传类型为代理上传（PROXY）的元数据，
     * 表示该存储源的文件上传需要通过代理中转。
     * </p>
     *
     * @return 存储源元数据对象
     */
    @Override
    public StorageSourceMetadata getStorageSourceMetadata() {
        StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
        // SFTP 上传采用代理中转方式
        storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
        return storageSourceMetadata;
    }

    /**
     * 销毁服务，释放资源。
     * <p>
     * 关闭 SFTP 客户端连接池，释放所有连接资源。
     * </p>
     */
    @Override
    public void destroy() {
        if (sftpClientPool != null) {
            sftpClientPool.close();
        }
    }
}
