package im.tlgxy.cloud.core.exception;

import lombok.Getter;

/**
 * 统一错误码枚举类
 * <p>
 * 定义了 TlgxyCloud 系统中所有可能的错误码及其对应的错误信息。
 * 错误码采用 5 位数字编码规则：
 * <ul>
 *   <li><b>5xxxx</b>：系统级异常，由系统内部错误导致，用户无法自行处理</li>
 *   <li><b>4xxxx</b>：业务级异常，用户可以根据错误信息自行排查或纠正</li>
 *   <li><b>3xxxx</b>：权限相关异常，用户无权限访问指定资源</li>
 *   <li><b>2xxxx</b>：授权校验异常，与系统授权码验证相关</li>
 * </ul>
 * 其中 4xxxx 业务错误的第二位细分：
 * <ul>
 *   <li><b>40xxx</b>：系统初始化相关错误</li>
 *   <li><b>41xxx</b>：前台（文件管理）相关错误</li>
 *   <li><b>42xxx</b>：登录认证相关错误</li>
 *   <li><b>43xxx</b>：管理员后台相关错误</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Getter
public enum ErrorCode {

    /**
     * 系统异常（通用），表示发生了未知的系统内部错误
     */
    SYSTEM_ERROR("50000", "系统异常"),
    /**
     * 无效或初始化失败的存储源，存储源未正确配置或初始化过程出错
     */
    INVALID_STORAGE_SOURCE("50001", "无效或初始化失败的存储源"),
    /**
     * 演示站点不允许此操作，在演示环境中禁用了某些敏感操作
     */
    DEMO_SITE_DISABLE_OPERATOR("50002", "演示站点不允许此操作"),

    /**
     * 业务异常 4xxxx.
     * 第二位为 0 时，是系统初始化相关错误
     * 第二位为 1 时，是前台(文件管理)错误
     * 第二位为 2 时，是登录错误
     * 第二位为 3 时，是管理员端错误
     */
    /** 通用业务操作失败 */
    BIZ_ERROR("40000", "操作失败"),
    /** 通用资源未找到 */
    BIZ_NOT_FOUND("40400", "NOT FOUND"),

    // 第二位为 0 时，是系统初始化相关错误
    /** 系统已初始化，不允许重复执行初始化操作 */
    BIZ_SYSTEM_ALREADY_INIT("40001", "系统已初始化，请勿重复初始化"),
    /** 系统初始化过程中发生错误 */
    BIZ_SYSTEM_INIT_ERROR("40002", "系统初始化错误"),

    // 第二位为 1 时，是前台(文件管理)错误
    /** 请求参数异常，参数格式不正确或缺少必要参数 */
    BIZ_BAD_REQUEST("41000", "请求参数异常"),
    /** 该存储源不支持代理下载功能 */
    BIZ_UNSUPPORTED_PROXY_DOWNLOAD("41001", "该存储源不支持代理下载"),
    /** 签名无效或下载地址已过期，直链/短链的签名验证失败 */
    BIZ_INVALID_SIGNATURE("41002", "签名无效或下载地址已过期"),
    /** 预览文本文件大小超出系统限制，文件太大无法在线预览 */
    BIZ_PREVIEW_FILE_SIZE_EXCEED("41003", "预览文本文件大小超出系统限制"),
    /** 请求的文件不存在 */
    BIZ_FILE_NOT_EXIST("41004", "文件不存在"),
    /** 请求频率过高，触发了系统的访问频率限制 */
    BIZ_ACCESS_TOO_FREQUENT("41005", "请求太频繁了，请稍后再试"),
    /** 上传的文件为空，未选择文件或文件大小为0 */
    BIZ_UPLOAD_FILE_NOT_EMPTY("41006", "上传文件不能为空"),
    /** 上传文件过程中发生错误 */
    BIZ_UPLOAD_FILE_ERROR("41010", "上传文件失败"),
    /** 上传文件超时，超过了系统配置的超时时间 */
    BIZ_UPLOAD_FILE_TIMEOUT_ERROR("41026", "上传文件超时"),
    /** 过期时间设置不合法，如设置为过去的时间 */
    BIZ_EXPIRE_TIME_ILLEGAL("41007", "过期时间不合法"),
    /** 非空文件夹不允许直接删除，需先清空文件夹内容 */
    BIZ_DELETE_FILE_NOT_EMPTY("41008", "非空文件夹不允许删除"),
    /** 文件名或路径存在安全隐患，如包含了 "../" 等路径遍历字符 */
    BIZ_FILE_PATH_ILLEGAL("41009", "文件名/路径存在安全隐患"),
    /** 当前系统设置不允许使用直链功能 */
    BIZ_DIRECT_LINK_NOT_ALLOWED("41011", "当前系统不允许使用直链"),
    /** 当前系统设置不允许使用短链功能 */
    BIZ_SHORT_LINK_NOT_ALLOWED("41012", "当前系统不允许使用短链"),
    /** 短链已过期，超过了设定的有效期 */
    BIZ_SHORT_LINK_EXPIRED("41013", "短链已失效"),
    /** 短链不存在，可能已被删除或从未创建 */
    BIZ_SHORT_LINK_NOT_FOUNT("41014", "短链不存在"),
    /** 直链已过期，超过了设定的有效期 */
    BIZ_DIRECT_LINK_EXPIRED("41015", "直链已失效"),
    /** 该存储类型不支持当前请求的操作 */
    BIZ_STORAGE_NOT_SUPPORT_OPERATION("41016", "该存储类型不支持此操作"),
    /** 指定的存储源不存在 */
    BIZ_STORAGE_NOT_FOUND("41017", "存储源不存在"),
    /** 对存储源执行了非法或未授权的操作 */
    BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION("41018", "非法或未授权的操作"),
    /** 当前用户无权限访问该文件或目录 */
    BIZ_STORAGE_SOURCE_FILE_FORBIDDEN("41019", "文件目录无访问权限"),
    /** 访问的文件夹设置了密码保护，需要输入密码 */
    BIZ_STORAGE_SOURCE_FOLDER_PASSWORD_REQUIRED("41020", "此文件夹需要密码"),
    /** 文件夹密码输入错误 */
    BIZ_STORAGE_SOURCE_FOLDER_PASSWORD_ERROR("41021", "密码错误"),
    /** 文件名包含不合法字符或格式不正确 */
    BIZ_INVALID_FILE_NAME("41022", "文件名不合法"),
    /** 请求的操作不被系统支持 */
    BIZ_UNSUPPORTED_OPERATION("41023", "不支持的操作"),
    /** FTP 客户端连接池已满，无法创建新的 FTP 连接 */
    BIZ_FTP_CLIENT_POOL_FULL("41024", "FTP 客户端连接池已满"),
    /** SFTP 客户端连接池已满，无法创建新的 SFTP 连接 */
    BIZ_SFTP_CLIENT_POOL_FULL("41025", "SFTP 客户端连接池已满"),
    /** 指定的文件夹不存在 */
    BIZ_FOLDER_NOT_EXIST("41026", "文件夹不存在"),
    /** 上传的文件类型不在允许的范围内 */
    BIZ_UPLOAD_FILE_TYPE_NOT_ALLOWED("41027", "不允许上传的文件"),
    /** 重命名的目标文件名不被允许，可能因为后缀名在禁止列表中 */
    BIZ_RENAME_FILE_TYPE_NOT_ALLOWED("41028", "不允许重命名到该名称"),
    /** 不支持的文件操作类型 */
    BIZ_UNSUPPORTED_OPERATION_TYPE("41029", "不支持的操作类型"),
    /** 自定义分享链接的 key 格式不正确，只能是字母、数字、下划线和短横线，长度为 3-8 位 */
    BIZ_CUSTOM_SHARE_LINK_KEY_FORMAT_ILLEGAL("41030", "自定义分享 key 格式不正确，只能包含字母、数字、下划线和短横线，长度为 3-8 位"),
    /** 分享链接的 key 已存在，需要更换一个唯一的 key */
    BIZ_SHARE_LINK_KEY_ALREADY_EXIST("41031", "分享 key 已存在"),
    /** 分享链接的过期时间必须是未来的某个时间点 */
    BIZ_SHARE_LINK_EXPIRY_MUST_BE_FUTURE("41032", "过期时间必须是未来的时间"),
    /** 分享链接不存在，可能已被删除 */
    BIZ_SHARE_LINK_NOT_EXIST("41033", "分享链接不存在"),
    /** 分享链接已过期 */
    BIZ_SHARE_LINK_EXPIRED("41034", "分享链接已过期"),
    /** 分享密码错误 */
    BIZ_SHARE_PASSWORD_ERROR("41036", "分享密码错误"),
    /** 获取分享文件列表失败 */
    BIZ_SHARE_FILE_LIST_ERROR("41037", "获取分享文件列表失败"),
    /** 获取分享文件的下载地址失败 */
    BIZ_SHARE_FILE_DOWNLOAD_ERROR("41038", "获取文件下载地址失败"),
    /** 获取分享文件信息失败 */
    BIZ_SHARE_FILE_INFO_ERROR("41039", "获取文件信息失败"),

    // 第二位为 2 时，是登录错误
    /** 未登录或未授权，用户需要先登录才能访问 */
    BIZ_UNAUTHORIZED("42000", "未登录或未授权"),
    /** 登录失败，账号或密码不正确 */
    BIZ_LOGIN_ERROR("42001", "登录失败, 账号或密码错误"),
    /** 验证码错误或已过期失效 */
    BIZ_VERIFY_CODE_ERROR("42002", "验证码错误或已失效"),

    // 第二位为 3 时，是管理员端错误
    /** 管理员操作失败（通用） */
    BIZ_ADMIN_ERROR("43000", "操作失败"),
    /** 指定的用户不存在 */
    BIZ_USER_NOT_EXIST("43001", "用户不存在"),
    /** 用户名已存在，无法创建重复用户 */
    BIZ_USER_EXIST("43002", "用户已存在"),
    /** 两次输入的密码不一致 */
    BIZ_PASSWORD_NOT_SAME("43003", "两次密码不一致"),
    /** 旧密码不匹配，修改密码时输入的旧密码错误 */
    BIZ_OLD_PASSWORD_ERROR("43004", "旧密码不匹配"),
    /** 不允许删除系统内置用户 */
    BIZ_DELETE_BUILT_IN_USER("43005", "不能删除内置用户"),
    /** 配置的存储类型不被系统支持 */
    BIZ_UNSUPPORTED_STORAGE_TYPE("43006", "不支持的存储类型"),
    /** 存储源别名（存储 key）已存在，需要更换 */
    BIZ_STORAGE_KEY_EXIST("43007", "存储源别名已存在"),
    /** 自动获取 SharePoint 网站列表失败 */
    BIZ_AUTO_GET_SHARE_POINT_SITES_ERROR("43008", "自动获取 SharePoint 网站列表失败"),
    /** 站点域名未配置，请先在站点设置中配置 */
    BIZ_ORIGINS_NOT_EMPTY("43009", "请先在 \"站点设置\" 中配置站点域名"),
    /** 双因素认证（2FA）验证码验证失败 */
    BIZ_2FA_CODE_ERROR("43010", "双因素认证验证失败"),
    /** 存储源初始化失败，可能是配置错误或网络不通 */
    BIZ_STORAGE_INIT_ERROR("43011", "存储源初始化失败"),
    /** 规则已存在，无法创建重复规则 */
    BIZ_RULE_EXIST("43012", "规则已存在"),
    /** 单点登录（SSO）配置已存在 */
    BIZ_SSO_PROVIDER_EXIST("43013", "单点登录配置已存在"),
    /** 该单点登录（SSO）配置未启用 */
    BIZ_SSO_PROVIDER_DISABLED("43014", "此单点登录未启用"),


    /**
     * 通用的无权限异常，用户已登录但没有对应的操作权限
     */
    NO_FORBIDDEN("30000", "没有权限"),
    /**
     * 没有自定义分享链接 key 的权限
     */
    NO_CUSTOM_SHARE_LINK_KEY_PERMISSION("30001", "没有自定义分享链接 key 的权限"),



    /**
     * 授权校验异常，与 TlgxyCloud 系统的授权码验证相关
     */
    /** 未填写授权码，需要先去后台设置中填写 */
    PRO_AUTH_CODE_EMPTY("20000", "请先去后台 \"基本设置\" 填写 \"授权码\""),
    /** Referer 验证失败，请求来源无效 */
    PRO_CHECK_REFERER_EMPTY("20001", "Referer 无效，请检查服务端设置，20001"),
    /** 服务器时间异常，授权校验时发现时间不同步 */
    PRO_CHECK_TIME_NO_SYNC("20002", "授权校验失败, 服务器时间异常，20002"),
    /** 授权码无效，请检查后台设置中的授权码是否正确 */
    PRO_AUTH_CODE_INVALID_ERROR("20003", "授权码无效, 请检查后台 \"站点设置\" 中的 \"授权码\" 20003"),
    /** 授权验证过程中发生了未知异常 */
    PRO_CHECK_UNKNOWN_ERROR("20004", "授权验证异常，未知异常，20098"),
    /** 授权相关消息提示（消息内容动态设置） */
    PRO_MSG_ERROR("20005", null);

    /**
     * 错误码，通常为 5 位数字字符串
     */
    private String code;

    /**
     * 错误信息，描述错误的具体原因
     */
    private String message;

    /**
     * 构造函数，创建包含错误码和错误信息的枚举实例
     *
     * @param code    错误码
     * @param message 错误信息
     */
    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 设置错误码，支持链式调用以在运行时动态修改错误码
     *
     * @param code 新的错误码
     * @return 返回当前枚举实例，支持链式调用
     */
    public ErrorCode setCode(String code) {
        this.code = code;
        return this;
    }

    /**
     * 设置错误信息，支持链式调用以在运行时动态修改错误信息
     *
     * @param message 新的错误信息
     * @return 返回当前枚举实例，支持链式调用
     */
    public ErrorCode setMessage(String message) {
        this.message = message;
        return this;
    }

}
