package im.tlgxy.cloud.module.storage.service.base;

import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 基础文件服务接口，定义了所有存储源需要实现的通用文件操作规范。
 * <p>
 * 该接口是 TlgxyCloud 存储源抽象层的顶层接口，所有存储源服务类都必须直接或间接实现此接口。
 * 它规定了文件/文件夹的增删改查、复制、移动、重命名、上传下载等核心操作的标准方法签名。
 * </p>
 * <p>
 * 接口方法可分为以下几类：
 * <ul>
 *   <li><b>查询类：</b>{@link #fileList(String)}、{@link #getFileItem(String)}</li>
 *   <li><b>创建/删除类：</b>{@link #newFolder(String, String)}、{@link #deleteFile(String, String)}、{@link #deleteFolder(String, String)}</li>
 *   <li><b>复制/移动类：</b>{@link #copyFile(String, String, String, String)}、{@link #copyFolder(String, String, String, String)}、
 *       {@link #moveFile(String, String, String, String)}、{@link #moveFolder(String, String, String, String)}</li>
 *   <li><b>重命名类：</b>{@link #renameFile(String, String, String)}、{@link #renameFolder(String, String, String)}</li>
 *   <li><b>上传/下载类：</b>{@link #getUploadUrl(String, String, Long)}、{@link #getDownloadUrl(String)}</li>
 *   <li><b>元数据/生命周期：</b>{@link #getStorageTypeEnum()}、{@link #destroy()}</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
public interface BaseFileService {

    /***
     * 获取指定路径下的文件及文件夹列表。
     * <p>
     * 该方法返回目标文件夹下的直接子文件和子文件夹列表，不包含子文件夹内的嵌套内容。
     * 不同存储源的实现可能支持分页查询、过滤等特性。
     * </p>
     *
     * @param   folderPath
     *          文件夹路径，相对于存储源根目录
     *
     * @return  文件及文件夹列表，如果路径不存在或为空则返回空列表
     *
     * @throws  Exception  获取文件列表过程中出现的异常
     */
    List<FileItemResult> fileList(String folderPath) throws Exception;

    /**
     * 获取单个文件或文件夹的详细信息。
     * <p>
     * 返回的结果包含文件/文件夹的名称、大小、最后修改时间、类型（文件/文件夹）、
     * 下载地址等元数据信息。
     * </p>
     *
     * @param   pathAndName
     *          文件/文件夹的完整路径及名称
     *
     * @return  单个文件/文件夹的信息，如果不存在则返回 null
     */
    @Nullable
    FileItemResult getFileItem(String pathAndName);

    /**
     * 在指定路径下创建新文件夹。
     * <p>
     * 如果目标位置已存在同名文件夹，行为取决于具体存储源的实现
     * （可能覆盖、重命名或抛出异常）。
     * </p>
     *
     * @param   path
     *          父文件夹路径
     *
     * @param   name
     *          新文件夹名称
     *
     * @return  true 表示创建成功，false 表示创建失败
     */
    boolean newFolder(String path, String name);

    /**
     * 删除指定路径下的文件。
     * <p>
     * 删除操作通常是不可逆的，具体存储源可能支持软删除（回收站）或永久删除。
     * </p>
     *
     * @param   path
     *          文件所在文件夹路径
     *
     * @param   name
     *          文件名称
     *
     * @return  true 表示删除成功，false 表示删除失败
     */
    boolean deleteFile(String path, String name);

    /**
     * 删除指定路径下的文件夹。
     * <p>
     * 不同存储源对文件夹删除的支持程度不同：
     * 部分存储源只支持删除空文件夹，部分支持递归删除非空文件夹。
     * </p>
     *
     * @param   path
     *          文件夹所在父路径
     *
     * @param   name
     *          文件夹名称
     *
     * @return  true 表示删除成功，false 表示删除失败
     */
    boolean deleteFolder(String path, String name);

    /**
     * 复制文件到目标路径。
     * <p>
     * 将指定文件复制到目标位置，源文件保留不变。
     * 如果目标位置已存在同名文件，行为取决于存储源的冲突处理策略。
     * </p>
     *
     * @param   path
     *          源文件所在文件夹路径
     *
     * @param   name
     *          源文件名称
     *
     * @param   targetPath
     *          目标文件夹路径
     *
     * @param   targetName
     *          目标文件名称（可与源文件不同，实现重命名复制）
     *
     * @return  true 表示复制成功，false 表示复制失败
     */
    boolean copyFile(String path, String name, String targetPath, String targetName);

    /**
     * 复制文件夹到目标路径。
     * <p>
     * 将指定文件夹及其全部内容复制到目标位置。
     * 并非所有存储源都支持文件夹复制操作（如 S3）。
     * </p>
     *
     * @param   path
     *          源文件夹所在父路径
     *
     * @param   name
     *          源文件夹名称
     *
     * @param   targetPath
     *          目标父路径
     *
     * @param   targetName
     *          目标文件夹名称
     *
     * @return  true 表示复制成功，false 表示复制失败
     */
    boolean copyFolder(String path, String name, String targetPath, String targetName);

    /**
     * 移动文件到目标路径。
     * <p>
     * 将指定文件移动到目标位置，源文件不再保留。
     * 如果目标位置已存在同名文件，行为取决于存储源的冲突处理策略。
     * </p>
     *
     * @param   path
     *          源文件所在文件夹路径
     *
     * @param   name
     *          源文件名称
     *
     * @param   targetPath
     *          目标文件夹路径
     *
     * @param   targetName
     *          目标文件名称（可与源文件不同，实现重命名移动）
     *
     * @return  true 表示移动成功，false 表示移动失败
     */
    boolean moveFile(String path, String name, String targetPath, String targetName);

    /**
     * 移动文件夹到目标路径。
     * <p>
     * 将指定文件夹及其全部内容移动到目标位置。
     * 并非所有存储源都支持文件夹移动操作（如 S3）。
     * </p>
     *
     * @param   path
     *          源文件夹所在父路径
     *
     * @param   name
     *          源文件夹名称
     *
     * @param   targetPath
     *          目标父路径
     *
     * @param   targetName
     *          目标文件夹名称
     *
     * @return  true 表示移动成功，false 表示移动失败
     */
    boolean moveFolder(String path, String name, String targetPath, String targetName);

    /**
     * 重命名文件。
     * <p>
     * 修改文件的名称，文件路径不变。
     * 不同存储源实现方式不同：有的直接支持重命名 API，有的通过"复制+删除"模拟。
     * </p>
     *
     * @param   path
     *          文件所在文件夹路径
     *
     * @param   name
     *          文件当前名称
     *
     * @param   newName
     *          文件新名称
     *
     * @return  true 表示重命名成功，false 表示重命名失败
     */
    boolean renameFile(String path, String name, String newName);

    /**
     * 重命名文件夹。
     * <p>
     * 修改文件夹的名称，文件夹路径不变。
     * 并非所有存储源都支持文件夹重命名操作（如 S3）。
     * </p>
     *
     * @param   path
     *          文件夹所在父路径
     *
     * @param   name
     *          文件夹当前名称
     *
     * @param   newName
     *          文件夹新名称
     *
     * @return  true 表示重命名成功，false 表示重命名失败
     */
    boolean renameFolder(String path, String name, String newName);

    /**
     * 获取文件上传地址（上传 URL）。
     * <p>
     * 根据不同存储源的实现，返回的上传地址可能是：
     * <ul>
     *   <li>代理上传地址（通过 TlgxyCloud 服务器中转）</li>
     *   <li>存储源直传地址（如 S3 预签名 URL、OneDrive 上传会话 URL）</li>
     * </ul>
     * </p>
     *
     * @param   path
     *          文件上传目标文件夹路径
     *
     * @param   name
     *          文件名称
     *
     * @param   size
     *          文件大小（字节），用于存储源判断是否需要分片上传等
     *
     * @return  文件上传地址（URL）
     */
    String getUploadUrl(String path, String name, Long size);

    /**
     * 获取文件下载地址。
     * <p>
     * 根据不同存储源的实现，返回的下载地址可能是：
     * <ul>
     *   <li>代理下载地址（通过 TlgxyCloud 服务器中转）</li>
     *   <li>存储源直链地址（如 OneDrive 的 @microsoft.graph.downloadUrl、S3 预签名 URL）</li>
     * </ul>
     * </p>
     * <p>
     * 路径契约：pathAndName 必须是相对存储源 basePath 的绝对路径（调用方已拼好用户根目录 /
     * 分享 sharePath）。本方法实现不得再调用 getCurrentUserBasePath() 二次拼接——其调用链
     * （直链 / 短链 / 代理回源 / 分享下载）常处于匿名或跨用户上下文，取不到正确的用户根目录；
     * 实现内部只拼 basePath 即可，可参照同属"绝对入参"的 downloadToStream 写法。
     * </p>
     *
     * @param   pathAndName
     *          相对 basePath 的绝对路径（已含用户根目录 / sharePath）
     *
     * @return  文件下载地址
     */
    String getDownloadUrl(String pathAndName);

    /**
     * 获取当前存储源的存储类型枚举。
     * <p>
     * 用于标识当前存储源的具体类型（如 S3、OneDrive、SharePoint、FTP 等），
     * 便于前端展示和业务逻辑判断。
     * </p>
     *
     * @return  存储源类型枚举值
     */
    StorageTypeEnum getStorageTypeEnum();

    /**
     * 销毁资源，在存储源关闭、删除或应用停止时调用。
     * <p>
     * 实现类应在此方法中释放占用的网络连接、文件句柄、线程池等系统资源，
     * 确保资源的正确回收。
     * </p>
     */
    void destroy();

}