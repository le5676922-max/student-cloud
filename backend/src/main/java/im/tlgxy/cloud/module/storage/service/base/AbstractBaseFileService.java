package im.tlgxy.cloud.module.storage.service.base;

import cn.hutool.core.util.ObjUtil;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.InitializeStorageSourceBizException;
import im.tlgxy.cloud.core.util.StrPool;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽象文件服务基类
 * <p>
 * 所有存储源文件服务的抽象基类，定义了存储源文件服务的通用行为。
 * 泛型参数 P 表示存储源特定的参数类型，必须实现 IStorageParam 接口。
 * </p>
 * <p>
 * 此类提供了存储源初始化、连接测试、获取当前用户基础路径等通用功能。
 * 子类需要实现 {@link #init()} 方法来完成特定存储源的初始化逻辑，
 * 以及 {@link #getStorageSourceMetadata()} 方法来提供存储源元数据。
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承 IStorageParam
 * @author zhaojun
 */
@Slf4j
public abstract class AbstractBaseFileService<P extends IStorageParam> implements BaseFileService {

    @Resource
    private UserStorageSourceService userStorageSourceService;

    /**
     * 存储源初始化参数，包含访问密钥、端点地址等配置信息
     */
    @Getter
    public P param;

    /**
     * 存储源是否已初始化成功
     */
    @Getter
    protected boolean isInitialized = false;

    /**
     * 存储源唯一标识 ID
     */
    @Getter
    public Integer storageId;

    /**
     * 存储源名称，用于显示和日志记录
     */
    @Getter
    private String name;

    /**
     * 初始化存储源
     * <p>
     * 设置存储源的名称、ID 和参数，并进行重复初始化检查。
     * 如果已经初始化过（name、storageId、param 都不为 null），则抛出异常。
     * 如果初始化参数有空值，也会抛出异常。
     * 初始化完成后调用子类实现的 {@link #init()} 方法。
     * </p>
     *
     * @param name      存储源名称
     * @param storageId 存储源 ID
     * @param param     存储源特定的参数对象
     */
    public void init(String name, Integer storageId, P param) {
        // 防止重复初始化
        if (!ObjUtil.hasNull(this.name, this.storageId, this.param)) {
            throw new IllegalStateException("请勿重复初始化");
        }
        // 检查初始化参数不能为空
        if (ObjUtil.hasEmpty(name, storageId, param)) {
            throw new IllegalStateException("初始化参数不能为空");
        }
        this.name = name;
        this.storageId = storageId;
        this.param = param;
        // 调用子类实现的初始化方法
        init();
    }

    /**
     * 初始化存储源
     * <p>
     * 子类必须实现此方法，完成特定存储源的初始化工作。
     * 在调用此方法前，{@link #storageId} 和 {@link #param} 属性已经被设置。
     * </p>
     */
    public abstract void init();

    /**
     * 测试存储源连接
     * <p>
     * 尝试获取根路径（"/"）的文件列表来验证连接是否正常。
     * 如果未抛出异常，则认为连接成功，将 isInitialized 置为 true。
     * 如果抛出异常，则包装为初始化存储源业务异常并向上抛出。
     * </p>
     *
     * @throws InitializeStorageSourceBizException 如果连接测试失败
     */
    public void testConnection() {
        try {
            // 尝试获取根路径的文件列表来验证连接
            fileList(StringUtils.SLASH);
            isInitialized = true;
        } catch (Exception e) {
            // 连接失败，抛出初始化异常
            throw new InitializeStorageSourceBizException(ErrorCode.BIZ_STORAGE_INIT_ERROR.getCode(), "初始化异常, 错误信息为: " + e.getMessage(), storageId, e);
        }
    }

    /**
     * 获取存储源的简要信息字符串
     * <p>
     * 格式：存储源 [id=1, name=xxx, type: FTP]
     * 主要用于日志输出，方便追踪和调试。
     * </p>
     *
     * @return 格式化的存储源简要信息字符串
     */
    protected String getStorageSimpleInfo() {
        return String.format("存储源 [id=%s, name=%s, type: %s]", storageId, name, getStorageTypeEnum().getDescription());
    }


    /**
     * 获取存储源元数据
     * <p>
     * 子类需要实现此方法，返回存储源的文件系统元数据信息，
     * 如根路径、文件系统类型等。
     * </p>
     *
     * @return 存储源元数据对象
     */
    public abstract StorageSourceMetadata getStorageSourceMetadata();

    /**
     * 获取当前用户在存储源中的基础路径
     * <p>
     * 优先级逻辑：
     * 1. 如果是分享访问（ShareAccessContext.isShareAccess() 为 true），
     *    返回分享链接中指定的基础路径。
     * 2. 否则，根据当前登录用户查询其在该存储源中的根路径设置。
     *    如果存储源未初始化完成，则使用管理员身份（ADMIN_ID）查询。
     * 3. 如果用户未设置根路径或根路径为空，则返回 "/"。
     * </p>
     *
     * @return 当前用户在存储源中的基础路径
     */
    public String getCurrentUserBasePath() {
        // 检查是否为分享访问，如果是则返回分享的基础路径
        if (ShareAccessContext.isShareAccess()) {
            return ShareAccessContext.getShareBasePath();
        }

        // 初始化连接检查使用管理员根路径；已初始化的业务访问必须读取登录身份。
        Integer userId = this.isInitialized ? TlgxyCloudAuthUtil.getCurrentUserId() : UserConstant.ADMIN_ID;
        UserStorageSource userStorageSource = userStorageSourceService.getByUserIdAndStorageId(userId, storageId);
        // 用户未设置根路径或根路径为空时，返回根路径 "/"
        if (userStorageSource == null || StringUtils.isEmpty(userStorageSource.getRootPath())) {
            return StrPool.SLASH;
        } else {
            return userStorageSource.getRootPath();
        }
    }


    /**
     * 销毁存储源，释放资源
     * <p>
     * 默认实现为空操作。子类可以重写此方法以实现自定义的资源释放逻辑，
     * 如关闭连接池、停止定时任务等。
     * </p>
     */
    @Override
    public void destroy() {

    }
}
