package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.extra.ftp.Ftp;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.util.ArrayUtils;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.FtpParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.storage.support.ftp.FtpClientFactory;
import im.tlgxy.cloud.module.storage.support.ftp.FtpClientPool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.*;

/**
 * FTP/SFTP 存储服务实现类。
 * <p>
 * 继承自 {@link AbstractProxyTransferService}，通过 FTP 协议与远程 FTP 服务器进行文件交互。
 * 使用 Apache Commons Pool2 连接池管理 FTP 客户端连接，支持主动模式和被动模式两种 FTP 连接方式。
 * 文件的上传和下载通过代理中转方式实现。
 * </p>
 * <p>
 * 作用域为 prototype，每次注入时创建新的实例，确保不同存储源的参数相互隔离。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Slf4j
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FtpServiceImpl extends AbstractProxyTransferService<FtpParam> {

    /**
     * FTP 客户端连接池，用于复用 FTP 连接
     */
    private FtpClientPool ftpClientPool;

    /**
     * FTP 主动模式常量
     */
    public static final String FTP_MODE_ACTIVE = "active";

    /**
     * FTP 被动模式常量
     */
    public static final String FTP_MODE_PASSIVE = "passive";

    /**
     * 初始化 FTP 服务。
     * <p>
     * 根据配置参数创建 FTP 客户端工厂和连接池：
     * 1. 使用配置的字符编码创建 FTP 客户端工厂
     * 2. 配置连接池参数（借用时测试连接、最大等待时间 15 秒）
     * 3. 创建 FTP 客户端连接池
     * </p>
     */
    @Override
    public void init() {
        // 根据配置的编码创建字符集对象
        Charset charset = Charset.forName(param.getEncoding());
        // 创建 FTP 客户端工厂
        FtpClientFactory factory = new FtpClientFactory(param.getHost(), param.getPort(), param.getUsername(), param.getPassword(), charset, param.getFtpMode());
        // 配置连接池参数
        GenericObjectPoolConfig<FtpClientFactory> config = new GenericObjectPoolConfig<>();
        config.setTestOnBorrow(true);  // 借用连接时进行有效性测试
        config.setMaxWait(Duration.ofSeconds(15));  // 最大等待时间 15 秒
        ftpClientPool = new FtpClientPool(factory, config);
    }

    /**
     * 从连接池中获取一个 FTP 客户端。
     *
     * @return FTP 客户端对象
     * @throws BizException 当连接池已满无法获取连接时抛出
     * @throws SystemException 当获取连接发生其他异常时抛出
     */
    public Ftp getClientFromPool() {
        try {
            return ftpClientPool.borrowObject();
        } catch (NoSuchElementException e) {
            throw new BizException(ErrorCode.BIZ_FTP_CLIENT_POOL_FULL);
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

    /**
     * 获取指定文件夹下的文件列表。
     * <p>
     * 通过 FTP 列出指定目录下的所有文件和子目录，
     * 跳过 "." 和 ".." 这两个特殊的目录项，将每个 FTPFile 转换为 FileItemResult。
     * </p>
     *
     * @param folderPath 文件夹路径
     * @return 文件/文件夹列表
     * @throws IOException IO 异常
     */
    @Override
    public List<FileItemResult> fileList(String folderPath) throws IOException {
        Ftp ftp = null;
        try {
            // 从连接池获取 FTP 客户端
            ftp = getClientFromPool();
            // 拼接完整路径：基础路径 + 用户基础路径 + 文件夹路径
            String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), folderPath);
            FTPFile[] ftpFiles = ftp.lsFiles(fullPath);
            List<FileItemResult> fileItemList = new ArrayList<>();

            // 遍历 FTP 文件列表，转换为 FileItemResult
            for (FTPFile ftpFile : ftpFiles) {
                // 跳过 ftp 的本目录和上级目录
                if (Arrays.asList(".", "..").contains(ftpFile.getName())) {
                    continue;
                }
                FileItemResult fileItemResult = ftpFileToFileItem(ftpFile, folderPath);
                fileItemList.add(fileItemResult);
            }
            return fileItemList;
        } finally {
            // 确保 FTP 客户端归还到连接池
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }


    /**
     * 根据路径和文件名获取单个文件项信息。
     * <p>
     * 列出文件所在目录的所有文件，然后按文件名匹配查找目标文件。
     * </p>
     *
     * @param pathAndName 文件的完整路径和名称
     * @return 文件项信息，未找到时返回 null
     */
    @Override
    public FileItemResult getFileItem(String pathAndName) {
        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            // 获取父目录路径
            String folderPath = FileUtils.getParentPath(pathAndName);
            String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), folderPath);
            FTPFile[] ftpFiles = ftp.lsFiles(fullPath);

            // 目录为空则返回 null
            if (ArrayUtils.isEmpty(ftpFiles)) {
                return null;
            }

            // 提取文件名
            String fileName = FileUtils.getName(pathAndName);

            // 匹配文件名查找目标文件
            for (FTPFile ftpFile : ftpFiles) {
                if (Objects.equals(ftpFile.getName(), fileName)) {
                    return ftpFileToFileItem(ftpFile, folderPath);
                }
            }
            return null;
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }


    /**
     * 在指定路径下创建新文件夹。
     *
     * @param path 父目录路径
     * @param name 新文件夹名称
     * @return 创建成功返回 true，失败返回 false
     */
    @Override
    public boolean newFolder(String path, String name) {
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            return ftp.mkdir(fullPath);
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }


    /**
     * 删除指定文件。
     *
     * @param path 文件所在目录路径
     * @param name 文件名
     * @return 删除成功返回 true，失败返回 false
     */
    @Override
    public boolean deleteFile(String path, String name) {
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            return ftp.delFile(fullPath);
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }


    /**
     * 删除指定文件夹。
     *
     * @param path 文件夹所在目录路径
     * @param name 文件夹名称
     * @return 删除成功返回 true，失败返回 false
     */
    @Override
    public boolean deleteFolder(String path, String name) {
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            return ftp.delDir(fullPath);
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }


    /**
     * 重命名文件。
     * <p>
     * FTP 中重命名通过在同一目录下移动文件实现。
     * </p>
     *
     * @param path    文件所在目录路径
     * @param name    原文件名
     * @param newName 新文件名
     * @return 重命名成功返回 true，失败返回 false
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        return moveFile(path, name, path, newName);
    }


    /**
     * 重命名文件夹。
     * <p>
     * FTP 中重命名文件夹与重命名文件使用相同逻辑。
     * </p>
     *
     * @param path    文件夹所在目录路径
     * @param name    原文件夹名称
     * @param newName 新文件夹名称
     * @return 重命名成功返回 true，失败返回 false
     */
    @Override
    public boolean renameFolder(String path, String name, String newName) {
        return renameFile(path, name, newName);
    }


    /**
     * 获取文件的下载链接。
     * <p>
     * 如果配置了自定义域名，直接拼接返回直链地址；
     * 否则通过代理下载方式生成下载 URL。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return 文件的下载 URL
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        // 有自定义域名时直接拼接直链
        if (StringUtils.isNotBlank(param.getDomain())) {
            return StringUtils.concat(param.getDomain(), StringUtils.encodeAllIgnoreSlashes(pathAndName));
        }
        // 无域名时使用代理下载
        return super.getProxyDownloadUrl(pathAndName);
    }



    /**
     * 将文件以流的形式下载并写入 HTTP 响应。
     * <p>
     * 从 FTP 服务器获取文件输入流，通过 {@link RequestHolder} 写入到 HTTP 响应中。
     * 如果配置了自定义域名，则不支持代理下载方式，会抛出异常。
     * </p>
     *
     * @param pathAndName 文件的路径和名称
     * @return HTTP 响应实体（本方法始终返回 null，实际响应由 RequestHolder 处理）
     * @throws IOException       IO 异常
     * @throws BizException     当配置了域名但仍使用代理下载时抛出
     */
    @Override
    public ResponseEntity<Resource> downloadToStream(String pathAndName) throws IOException {
        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            // 如果配置了域名，还访问代理下载 URL, 则抛出异常进行提示.
            if (StringUtils.isNotEmpty(param.getDomain())) {
                throw new BizException(ErrorCode.BIZ_UNSUPPORTED_PROXY_DOWNLOAD);
            }

            // 拼接完整路径
            pathAndName = StringUtils.concat(param.getBasePath(), pathAndName);
            String fileName = FileUtils.getName(pathAndName);
            // 如果启用了断点续传，获取文件大小
            Long fileSize = param.isEnableRange() ? Convert.toLong(ftp.getClient().getSize(pathAndName),0L) : null;

            // 获取文件输入流并写入响应
            InputStream inputStream = ftp.getClient().retrieveFileStream(pathAndName);
            RequestHolder.writeFile(inputStream, fileName, fileSize, false, param.isProxyLinkForceDownload());
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
        return null;
    }


    /**
     * 获取文件上传 URL。
     * <p>
     * FTP 使用代理上传来实现文件上传。
     * </p>
     *
     * @param path 上传目标路径
     * @param name 文件名
     * @param size 文件大小
     * @return 代理上传 URL
     */
    @Override
    public String getUploadUrl(String path, String name, Long size) {
        return super.getProxyUploadUrl(path, name);
    }


    /**
     * 上传文件到 FTP 服务器。
     * <p>
     * 从输入流读取文件内容，上传到 FTP 服务器的指定目录。
     * </p>
     *
     * @param pathAndName 上传目标路径和文件名
     * @param inputStream 文件输入流
     * @param size        文件大小
     */
    @Override
    public void uploadFile(String pathAndName, InputStream inputStream, Long size) {
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), pathAndName);
        String fileName = FileUtils.getName(pathAndName);
        String folderName = FileUtils.getParentPath(fullPath);

        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            // 上传文件到指定目录
            ftp.upload(folderName, fileName, inputStream);
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }

    /**
     * 复制文件。
     * <p>
     * FTP 协议不直接支持文件复制操作，因此抛出不支持的操作异常。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名
     * @param targetPath 目标路径
     * @param targetName 目标文件名
     * @throws BizException 始终抛出，因为 FTP 不支持此操作
     */
    @Override
    public boolean copyFile(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_UNSUPPORTED_OPERATION);
    }

    /**
     * 复制文件夹。
     * <p>
     * FTP 协议不直接支持文件夹复制操作，因此抛出不支持的操作异常。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名
     * @param targetPath 目标路径
     * @param targetName 目标文件夹名
     * @throws BizException 始终抛出，因为 FTP 不支持此操作
     */
    @Override
    public boolean copyFolder(String path, String name, String targetPath, String targetName) {
        throw new BizException(ErrorCode.BIZ_UNSUPPORTED_OPERATION);
    }

    /**
     * 移动文件（重命名）。
     * <p>
     * 通过 FTP 的 rename 命令实现文件的移动/重命名。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名
     * @param targetPath 目标路径
     * @param targetName 目标文件名
     * @return 移动成功返回 true
     * @throws SystemException 当 FTP 操作发生 IO 异常时抛出
     */
    @Override
    public boolean moveFile(String path, String name, String targetPath, String targetName) {
        // 拼接源路径和目标路径
        String srcPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        String distPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), targetPath, targetName);

        Ftp ftp = null;
        try {
            ftp = getClientFromPool();
            // 使用 FTP rename 命令实现移动
            return ftp.getClient().rename(srcPath, distPath);
        } catch (IOException e) {
            throw new SystemException(e);
        } finally {
            if (ftp != null) {
                ftpClientPool.returnObject(ftp);
            }
        }
    }

    /**
     * 移动文件夹。
     * <p>
     * FTP 中移动文件夹与移动文件使用相同的 rename 命令。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名
     * @param targetPath 目标路径
     * @param targetName 目标文件夹名
     * @return 移动成功返回 true
     */
    @Override
    public boolean moveFolder(String path, String name, String targetPath, String targetName) {
        return moveFile(path, name, targetPath, targetName);
    }

    /**
     * 将 FTP 文件对象转换为统一的文件项结果对象。
     *
     * @param ftpFile    FTP 文件对象
     * @param folderPath 文件所在的文件夹路径
     * @return 统一的文件项结果对象
     */
    private FileItemResult ftpFileToFileItem(FTPFile ftpFile, String folderPath) {
        FileItemResult fileItemResult = new FileItemResult();
        fileItemResult.setName(ftpFile.getName());
        fileItemResult.setSize(ftpFile.getSize());
        fileItemResult.setTime(ftpFile.getTimestamp().getTime());
        // 根据是否为目录设置类型
        fileItemResult.setType(ftpFile.isDirectory() ? FileTypeEnum.FOLDER : FileTypeEnum.FILE);
        fileItemResult.setPath(folderPath);

        // 文件类型时需要设置下载 URL
        if (fileItemResult.getType() == FileTypeEnum.FILE) {
            fileItemResult.setUrl(getDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), folderPath, fileItemResult.getName())));
        }
        return fileItemResult;
    }

    /**
     * 获取存储源的元数据信息。
     * <p>
     * FTP 存储源使用代理上传方式。
     * </p>
     *
     * @return 存储源元数据，上传类型为 PROXY
     */
    @Override
    public StorageSourceMetadata getStorageSourceMetadata() {
        StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
        storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
        return storageSourceMetadata;
    }

    /**
     * 返回当前存储类型枚举。
     *
     * @return 存储类型为 FTP
     */
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.FTP;
    }

    /**
     * 销毁 FTP 服务，关闭连接池释放资源。
     */
    @Override
    public void destroy() {
        if (ftpClientPool != null) {
            ftpClientPool.close();
        }
    }

}