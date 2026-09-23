package im.tlgxy.cloud.core.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import im.tlgxy.cloud.core.controller.FrontIndexController;
import im.tlgxy.cloud.core.exception.biz.*;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.ErrorPageBizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.exception.status.*;
import im.tlgxy.cloud.core.exception.system.UploadFileFailSystemException;
import im.tlgxy.cloud.core.exception.system.TlgxyCloudAuthorizationSystemException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.sqlite.SQLiteException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 全局异常处理器
 * <p>
 * 使用 Spring 的 {@link ControllerAdvice} 注解统一拦截和处理整个应用中抛出的异常。
 * 根据异常类型的不同，返回对应的 HTTP 状态码和错误信息。
 * </p>
 * <p>
 * 异常处理策略：
 * <ul>
 *   <li><b>状态码异常 (status exception)</b>：对应 HTTP 标准状态码，如 401、403、404、405、400。
 *       对于 AJAX 请求返回 JSON 格式错误信息，对于浏览器直接访问则重定向到对应的错误页面。</li>
 *   <li><b>业务异常 (biz exception)</b>：对应系统中的各种业务错误，记录 warn 级别日志并返回错误信息。</li>
 *   <li><b>系统异常 (system exception)</b>：对应系统级别的严重错误，记录 error 级别日志。</li>
 *   <li><b>通用异常</b>：兜底处理未明确分类的异常，根据异常类型决定日志输出策略。</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@ControllerAdvice
@Slf4j
@Order(1)
public class GlobalExceptionHandler {

    /**
     * 线程局部变量，用于在异常处理链中传递自定义的异常消息
     */
    private static final ThreadLocal<String> exceptionMessage = new ThreadLocal<>();

    /**
     * 系统配置服务，用于获取各类错误页面的重定向 URL
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 前端首页控制器，用于在 404 时返回 index.html 内容
     */
    @Resource
    private FrontIndexController frontIndexController;

    /**
     * 查找异常根因的最大递归深度，防止无限循环
     */
    private static final int MAX_FIND_CAUSE_EXCEPTION_DEPTH = 10;

    // ---------------------- status exception start ----------------------

    /**
     * 处理未授权访问异常（401）
     * <p>
     * 当用户未进行身份认证时触发。对于 AJAX 请求返回 401 状态的 JSON 数据，
     * 对于浏览器直接访问则重定向到系统配置的未授权页面。
     * </p>
     *
     * @return AJAX 请求返回错误 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(value = UnauthorizedAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public AjaxJson<?> unauthorizedAccessException() {
        // AJAX 请求直接返回 JSON 格式的错误信息
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getUnauthorizedResult();
        }
        // 浏览器直接访问则重定向到未授权页面
        try {
            String unauthorizedUrl = systemConfigService.getUnauthorizedUrl();
            RequestHolder.getResponse().sendRedirect(unauthorizedUrl);
        } catch (IOException ex) {
            return AjaxJson.getUnauthorizedResult();
        }

        return null;
    }

    /**
     * 处理角色权限不足异常（403）
     * <p>
     * 当用户已登录但不具备所需角色时触发（如普通用户访问管理员页面）。
     * </p>
     *
     * @return AJAX 请求返回错误 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(value = {
            NotRoleException.class
    })
    @ResponseBody
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxJson<?> forbiddenAccessException() {
        // AJAX 请求直接返回 JSON 格式的错误信息
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getForbiddenResult();
        }
        // 浏览器直接访问则重定向到禁止访问页面
        try {
            String forbiddenUrl = systemConfigService.getForbiddenUrl();
            RequestHolder.getResponse().sendRedirect(forbiddenUrl);
        } catch (IOException ex) {
            return AjaxJson.getForbiddenResult();
        }

        return null;
    }

    /**
     * 处理自定义禁止访问异常（403）
     * <p>
     * 当系统逻辑判断用户无权限访问某资源时主动抛出的异常，支持携带自定义错误码和错误信息。
     * </p>
     *
     * @param e 禁止访问异常，包含错误码和错误信息
     * @return AJAX 请求返回错误 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(value = ForbiddenAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxJson<?> forbiddenAccessException(ForbiddenAccessException e) {
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }
        try {
            String forbiddenUrl = systemConfigService.getForbiddenUrl(e.getCode(), e.getMessage());
            RequestHolder.getResponse().sendRedirect(forbiddenUrl);
        } catch (IOException ex) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }

        return null;
    }

    /**
     * 处理资源未找到异常（404）
     * <p>
     * 当用户请求的资源不存在时触发，支持携带自定义错误码和错误信息。
     * </p>
     *
     * @param e 未找到异常，包含错误码和错误信息
     * @return AJAX 请求返回错误 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(value = NotFoundAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public AjaxJson<?> notFoundAccessException(NotFoundAccessException e) {
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }
        try {
            String notFoundUrl = systemConfigService.getNotFoundUrl(e.getCode(), e.getMessage());
            RequestHolder.getResponse().sendRedirect(notFoundUrl);
        } catch (IOException ex) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }

        return null;
    }


    /**
     * 处理 Spring 静态资源未找到异常
     * <p>
     * 所有未找到的页面都跳转到首页，用于解决 Vue History 路由模式下直接访问前端路径导致 404 的问题。
     * 该方法读取 index.html 文件，修改 title 和 favicon 后返回。
     * </p>
     *
     * @return index.html 的内容，由前端路由接管处理
     */
    @ExceptionHandler(value = NoResourceFoundException.class)
    @ResponseBody
    public String notFoundAccessException() {
        return frontIndexController.redirect().getBody();
    }

    /**
     * 处理方法不允许异常（405）
     * <p>
     * 当请求的 HTTP 方法（如 GET、POST）不被目标接口支持时触发。
     * </p>
     *
     * @param e 方法不允许异常
     * @return 包含错误码和错误信息的 JSON
     */
    @ExceptionHandler(value = MethodNotAllowedAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public AjaxJson<String> methodNotAllowedAccessException(MethodNotAllowedAccessException e) {
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理错误请求异常（400）
     * <p>
     * 当请求参数格式不正确或服务器无法理解请求时触发。
     * </p>
     *
     * @param e 错误请求异常
     * @return 包含错误码和错误信息的 JSON
     */
    @ExceptionHandler(value = BadRequestAccessException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxJson<String> badRequestAccessException(BadRequestAccessException e) {
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    // ---------------------- status exception end ----------------------




    // ---------------------- biz exception start ----------------------

    /**
     * 处理第三方 API 请求异常
     * <p>
     * 当系统请求第三方 API（如 OneDrive API、SharePoint API）时返回非 2xx 状态码时触发。
     * 记录请求地址、响应状态码和响应内容，便于排查第三方接口问题。
     * </p>
     *
     * @param e API 请求异常，包含请求 URL、响应码和响应体
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = APIHttpRequestBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> apiHttpRequestBizException(APIHttpRequestBizException e) {
        log.warn("请求第三方 API 异常, 请求地址: {}, 响应码: {}, 响应体: {}", e.getUrl(), e.getResponseCode(), e.getResponseBody());
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理文件路径安全异常
     * <p>
     * 当用户提供的文件路径包含安全隐患（如 "../" 路径遍历攻击）时触发。
     * </p>
     *
     * @param e 文件路径安全异常，包含存在风险的路径
     * @return 包含错误信息的 JSON，HTTP 状态码为 400
     */
    @ExceptionHandler(value = FilePathSecurityBizException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxJson<String> filePathSecurityBizException(FilePathSecurityBizException e) {
        log.warn("获取文件路径存在安全风险, 文件路径: {}", e.getPath());
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理预览文件内容获取异常
     * <p>
     * 当系统尝试获取文本文件内容用于在线预览但失败时触发（如文件不存在、网络不可达等）。
     * </p>
     *
     * @param e 预览文件内容异常，包含文件 URL
     * @return 包含友好错误提示的 JSON
     */
    @ExceptionHandler(value = GetPreviewTextContentBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> getPreviewTextContentBizException(GetPreviewTextContentBizException e) {
        log.warn("获取预览文件内容失败, 文件 url: {}", e.getUrl(), e);
        return new AjaxJson<>(e.getCode(), "预览文件内容失败, 请联系管理员.");
    }

    /**
     * 处理存储源初始化异常
     * <p>
     * 当存储源（如本地存储、FTP、S3 等）在初始化过程中发生错误时触发。
     * </p>
     *
     * @param e 存储源初始化异常，包含存储源 ID
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = InitializeStorageSourceBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> initializeStorageSourceBizException(InitializeStorageSourceBizException e) {
        log.error("存储源初始化失败, 存储源 ID: {}.", e.getStorageId(), e);
        return new AjaxJson<>(e.getCode(), "存储源初始化失败：" + e.getMessage());
    }

    /**
     * 处理存储源文件/目录禁止访问异常
     * <p>
     * 当用户尝试访问未被授权的存储源文件或目录时触发（如越权访问其他用户的文件）。
     * </p>
     *
     * @param e 禁止访问异常，包含存储源 ID 和文件路径
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = StorageSourceFileForbiddenAccessBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> storageSourceFileForbiddenAccessBizException(StorageSourceFileForbiddenAccessBizException e) {
        log.warn("尝试访问不被授权的文件/目录, 存储源 ID: {}: 目录: {}", e.getStorageId(), e.getPath());
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理存储源非法操作异常
     * <p>
     * 当用户对存储源执行了不被允许的操作（如对只读存储执行删除操作）时触发。
     * </p>
     *
     * @param e 非法操作异常，包含存储源 ID 和操作类型
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = StorageSourceIllegalOperationBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> storageSourceIllegalOperationBizException(StorageSourceIllegalOperationBizException e) {
        log.warn("存储源非法或未授权的操作, 存储源 ID: {}, 操作类型: {}", e.getStorageId(), e.getAction());
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理跨域异常
     * <p>
     * 当跨域请求被拒绝或配置错误时触发。
     * </p>
     *
     * @param e 跨域异常
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = CorsBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> corsBizException(CorsBizException e) {
        log.warn("跨域异常:", e);
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理需要跳转错误页面的业务异常
     * <p>
     * 此类异常通常来自浏览器的直接页面访问（而非 AJAX 请求），
     * 需要重定向到系统配置的错误页面显示友好的错误提示。
     * </p>
     *
     * @param e 错误页面异常
     * @return AJAX 请求返回 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(value = ErrorPageBizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<?> errorPageBizException(ErrorPageBizException e) {
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }
        try {
            String errorPageUrl = systemConfigService.getErrorPageUrl(e.getCode(), e.getMessage());
            RequestHolder.getResponse().sendRedirect(errorPageUrl);
        } catch (IOException ex) {
            return AjaxJson.getError(e.getCode(), e.getMessage());
        }

        return null;
    }

    /**
     * 处理通用业务异常
     * <p>
     * 兜底处理所有未被更具体的异常处理器捕获的业务异常。
     * </p>
     *
     * @param e 业务异常
     * @return 包含错误码和错误信息的 JSON
     */
    @ExceptionHandler(value = BizException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> bizException(BizException e) {
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    // ---------------------- biz exception end ----------------------


    // ---------------------- system exception end ----------------------

    /**
     * 处理文件上传失败系统异常
     * <p>
     * 记录上传失败时的详细信息：存储类型、上传路径、输入流状态、响应码和响应体，
     * 便于排查上传失败的原因。
     * </p>
     *
     * @param e 上传失败异常
     * @return 包含错误信息的 JSON
     */
    @ExceptionHandler(value = UploadFileFailSystemException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<String> uploadFileFailSystemException(UploadFileFailSystemException e) {
        log.warn("上传文件失败, 存储类型: {}, 上传路径: {}, 输入流可用字节数: {}, 响应码: {}, 响应体: {}",
                e.getStorageTypeEnum(), e.getUploadPath(), e.getInputStreamAvailable(), e.getResponseCode(), e.getResponseBody());
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理 TlgxyCloud 授权异常
     * <p>
     * 当系统授权码验证失败时触发，如授权码无效、过期等。
     * </p>
     *
     * @param e 授权异常
     * @return 包含错误码和错误信息的 JSON
     */
    @ExceptionHandler(value = TlgxyCloudAuthorizationSystemException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<?> tlgxyCloudAuthorizationSystemException(TlgxyCloudAuthorizationSystemException e) {
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }

    /**
     * 处理通用系统异常
     * <p>
     * 兜底处理所有未被更具体的异常处理器捕获的系统级异常。
     * </p>
     *
     * @param e 系统异常
     * @return 包含错误码和错误信息的 JSON
     */
    @ExceptionHandler(value = SystemException.class)
    @ResponseBody
    @ResponseStatus
    public AjaxJson<?> systemException(SystemException e) {
        return new AjaxJson<>(e.getCode(), e.getMessage());
    }



    // ---------------------- system exception end ----------------------



    // ---------------------- common exception end ----------------------

    /**
     * 处理参数验证异常
     * <p>
     * 处理 Spring Validation 框架抛出的参数验证异常（{@link MethodArgumentNotValidException} 和 {@link BindException}），
     * 将所有字段级别的验证错误汇总后返回，HTTP 状态码为 400。
     * </p>
     *
     * @param e 参数验证异常
     * @return 包含字段名和对应错误信息映射的 JSON
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class, BindException.class})
    @ResponseBody
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    public AjaxJson<Map<String, String>> handleValidException(Exception e) {
        // 根据异常类型获取 BindingResult
        BindingResult bindingResult = null;
        if (e instanceof MethodArgumentNotValidException) {
            bindingResult = ((MethodArgumentNotValidException) e).getBindingResult();
        } else if (e instanceof BindException) {
            bindingResult = ((BindException) e).getBindingResult();
        }
        Map<String, String> errorMap = new HashMap<>(16);

        // 遍历所有字段验证错误，构建字段名 -> 错误信息的映射
        Optional.ofNullable(bindingResult)
                .map(BindingResult::getFieldErrors)
                .ifPresent(fieldErrors -> {
                    for (FieldError fieldError : fieldErrors) {
                        errorMap.put(fieldError.getField(), fieldError.getDefaultMessage());
                    }
                });
        return new AjaxJson<>(ErrorCode.BIZ_BAD_REQUEST.getCode(), ErrorCode.BIZ_BAD_REQUEST.getMessage(), errorMap);

    }

    /**
     * 处理文件未找到异常
     * <p>
     * 当请求的静态文件不存在时触发，返回 404 状态码。
     * </p>
     *
     * @return 文件不存在的错误 JSON
     */
    @ExceptionHandler({FileNotFoundException.class})
    @ResponseBody
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public AjaxJson<Void> fileNotFound() {
        return AjaxJson.getError("文件不存在");
    }


    /**
     * 处理登录异常（未登录）
     * <p>
     * 当用户未登录但访问了需要登录的接口时触发（由 Sa-Token 框架抛出）。
     * AJAX 请求返回 401 JSON，浏览器直接访问则重定向到登录页面。
     * </p>
     *
     * @param e 未登录异常
     * @return AJAX 请求返回错误 JSON，浏览器请求重定向后返回 null
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public AjaxJson<?> handlerNotLoginException(NotLoginException e) {
        if (RequestHolder.isAxiosRequest()) {
            return AjaxJson.getUnauthorizedResult();
        }
        try {
            // 获取前端域名并拼接登录页面路径
            String domain = systemConfigService.getRealFrontDomain();
            if (StringUtils.isBlank(domain)) {
                domain = "";
            }
            String loginUrl = StringUtils.concat(domain, "/login");
            RequestHolder.getResponse().sendRedirect(loginUrl);
        } catch (IOException ex) {
            return AjaxJson.getUnauthorizedResult();
        }

        return null;
    }

    /**
     * 兜底异常处理器
     * <p>
     * 处理所有未被上述特定处理器捕获的异常。根据异常类型采取不同的处理策略：
     * <ul>
     *   <li>{@link BizException}：仅打印异常信息，不打印堆栈（属于正常业务流程中的异常）</li>
     *   <li>{@link ClientAbortException}：忽略（客户端主动断开连接）</li>
     *   <li>SQLite 数据库锁异常：返回自定义错误提示</li>
     *   <li>其他异常：打印完整的异常信息和堆栈</li>
     * </ul>
     * </p>
     *
     * @param e 未被其他处理器捕获的异常
     * @return 包含错误信息的 JSON，部分异常可能返回 null
     */
    @ExceptionHandler
    @ResponseBody
    @ResponseStatus
    public AjaxJson<?> extraExceptionHandler(Exception e) {
        // 根据异常类型决定日志输出策略
        ExceptionType exceptionType = getExceptionType(e);
        if (exceptionType == ExceptionType.IGNORE_PRINT_STACK_TRACE_EXCEPTION) {
            // 业务异常：仅记录消息，不打印堆栈
            log.warn(e.getMessage());
        } else if (exceptionType == ExceptionType.OTHER) {
            // 未知异常：记录完整堆栈
            log.error(e.getMessage(), e);
        } else if (exceptionType == ExceptionType.SPECIFY_MESSAGE_EXCEPTION) {
            // 特定异常：使用自定义消息替代原始异常消息
            if (exceptionMessage.get() != null) {
                String message = exceptionMessage.get();
                log.error("发生异常: {}", message,e );
                exceptionMessage.remove();
                return AjaxJson.getError(message);
            }
        } else if (exceptionType == ExceptionType.IGNORE_EXCEPTION) {
            // 忽略异常（如客户端主动断开连接），不记录也不返回错误
            return null;
        }

        // 根据异常类型返回合适的错误信息
        if (e.getClass() == Exception.class) {
            // 通用 Exception 类不暴露具体原因，返回友好提示
            return AjaxJson.getError("系统异常, 请联系管理员");
        } else {
            // 其他具体异常返回异常消息
            return AjaxJson.getError(e.getMessage());
        }
    }

    /**
     * 递归查找异常类型，判断当前异常的日志处理策略
     * <p>
     * 通过遍历异常链（getCause()）来查找底层异常类型，最多查找 {@link #MAX_FIND_CAUSE_EXCEPTION_DEPTH} 层。
     * </p>
     *
     * @param e 需要分析的异常
     * @return 异常处理类型枚举
     */
    private static ExceptionType getExceptionType(Exception e) {
        int findCauseCount = 0;
        // 递归遍历异常链，查找已知的异常类型
        do {
            if (e instanceof BizException) {
                // 业务异常：不打印堆栈
                return ExceptionType.IGNORE_PRINT_STACK_TRACE_EXCEPTION;
            } else if (e instanceof ClientAbortException) {
                // 客户端断开连接：完全忽略
                return ExceptionType.IGNORE_EXCEPTION;
            } else if (e instanceof SQLiteException && e.getMessage().contains("database is locked")) {
                // 数据库锁定：使用自定义消息
                exceptionMessage.set("数据库繁忙，请稍后再试");
                return ExceptionType.SPECIFY_MESSAGE_EXCEPTION;
            }
            e = (Exception) e.getCause();
            findCauseCount++;
        } while (e != null && findCauseCount < MAX_FIND_CAUSE_EXCEPTION_DEPTH);

        // 未匹配到已知类型，按其他异常处理
        return ExceptionType.OTHER;
    }

    /**
     * 异常处理类型枚举
     * <p>
     * 定义了全局异常处理器对不同类型异常的日志输出和处理策略。
     * </p>
     */
    enum ExceptionType {
        /**
         * 完全忽略此类异常，不打印任何日志也不返回错误信息
         * （如客户端主动断开连接时的 ClientAbortException）
         */
        IGNORE_EXCEPTION,

        /**
         * 仅打印异常信息（warn 级别），不打印堆栈跟踪
         * （如业务异常，属于正常业务流程，无需打印堆栈）
         */
        IGNORE_PRINT_STACK_TRACE_EXCEPTION,

        /**
         * 不打印堆栈信息，但使用自定义的异常消息替代原始消息
         * （如数据库锁定异常，返回"数据库繁忙，请稍后再试"）
         */
        SPECIFY_MESSAGE_EXCEPTION,

        /**
         * 其他未分类的异常，打印完整的异常信息和堆栈跟踪（error 级别）
         */
        OTHER;
    }
}
