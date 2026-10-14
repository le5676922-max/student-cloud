package im.tlgxy.cloud.module.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.LoginVerifyModeEnum;
import im.tlgxy.cloud.module.user.model.request.ResetAdminUserNameAndPasswordRequest;
import im.tlgxy.cloud.module.user.model.request.UpdateUserPwdRequest;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.model.result.CheckLoginResult;
import im.tlgxy.cloud.module.user.model.result.LoginResult;
import im.tlgxy.cloud.module.user.model.result.LoginVerifyImgResult;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.utils.PasswordCredentialFingerprint;
import im.tlgxy.cloud.module.user.service.DynamicLoginEntryService;
import im.tlgxy.cloud.module.user.service.login.ImgVerifyCodeService;
import im.tlgxy.cloud.module.user.service.login.LoginService;
import im.tlgxy.cloud.module.user.util.LoginEntryPathUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;

/**
 * 用户控制器
 * <p>
 * 提供用户认证相关的 REST API 接口，包括：
 * 1. 用户登录和注销
 * 2. 登录验证方式查询（图形验证码 / 两步验证）
 * 3. 图形验证码获取
 * 4. 登录状态检测
 * 5. 修改密码
 * 6. 重置管理员密码（调试模式）
 * 7. 安全登录入口的动态注册与校验
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Tag(name = "用户接口")
@ApiSort(6)
@RestController
@RequestMapping("/user")
public class UserController {

    /**
     * 用户服务，处理用户相关的业务逻辑
     */
    @Resource
    private UserService userService;

    /**
     * 登录服务，处理登录验证的核心逻辑
     */
    @Resource
    private LoginService loginService;

    /**
     * 系统配置服务，获取系统级别的配置参数
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 图形验证码服务，生成和管理图形验证码
     */
    @Resource
    private ImgVerifyCodeService imgVerifyCodeService;

    /**
     * TlgxyCloud 属性配置，包含调试模式等全局配置
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 动态登录入口服务，用于运行时动态注册和切换登录请求路径
     */
    @Resource
    private DynamicLoginEntryService dynamicLoginEntryService;

    /**
     * 应用启动完成后初始化安全登录入口
     * <p>
     * 在 Spring 应用完全启动后，读取系统配置中保存的安全登录入口路径，
     * 动态注册登录请求的映射路径。使用 Spring 代理对象进行注册，
     * 以确保 AOP 切面（如 LoginLogAspect）能正常拦截登录请求。
     * </p>
     *
     * @throws NoSuchMethodException 如果找不到 doLogin 方法时抛出
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initSecureLoginEntry() throws NoSuchMethodException {
        // 获取 doLogin 方法的反射对象
        Method doLoginMethod = UserController.class.getMethod("doLogin", UserLoginRequest.class);
        SystemConfigDTO systemConfigDTO = systemConfigService.getSystemConfig();
        String secureLoginEntry = systemConfigDTO.getSecureLoginEntry();
        // 构建动态请求映射信息
        RequestMappingInfo requestMappingInfo = dynamicLoginEntryService.buildLoginRequestMappingInfo(secureLoginEntry);
        // 使用代理对象注册，否则 Spring MVC 反射调用时会绕过 AOP，LoginLogAspect 等切面将失效
        UserController proxy = SpringUtil.getBean(UserController.class);
        dynamicLoginEntryService.registerMappingHandlerMapping(SystemConfig.SECURE_LOGIN_ENTRY_NAME, requestMappingInfo, proxy, doLoginMethod);
        log.info("注册安全登录入口成功，当前登录路径为: {} ", LoginEntryPathUtils.resolveLoginPath(secureLoginEntry));
    }

    /**
     * 校验安全登录入口路径
     * <p>
     * 验证客户端请求的登录入口路径是否与系统配置的安全登录入口路径匹配。
     * 如果系统未配置安全登录入口（为 null），则默认通过校验。
     * </p>
     *
     * @param entry 客户端提交的登录入口路径
     * @return 校验结果，匹配成功返回成功，否则返回错误提示
     */
    @ApiOperationSupport(order = 0)
    @Operation(summary = "校验安全登录入口")
    @GetMapping("/login/entry/validate")
    public AjaxJson<Void> validateLoginEntry(@RequestParam(value = "entry", required = false, defaultValue = "") String entry) {
        SystemConfigDTO systemConfigDTO = systemConfigService.getSystemConfig();
        // 比较客户端入口与系统配置的入口是否一致（null 表示未配置，允许通过）
        boolean matched = systemConfigDTO.getSecureLoginEntry() == null || StringUtils.equals(systemConfigDTO.getSecureLoginEntry(), entry);
        if (!matched) {
            return AjaxJson.getError("安全登录入口不正确");
        }
        return AjaxJson.getSuccess();
    }

    /**
     * 用户登录
     * <p>
     * 处理用户的登录请求。首先调用登录服务进行验证（包括密码验证、图形验证码验证、两步验证等），
     * 验证通过后使用 Sa-Token 框架完成登录会话的创建，并返回包含 token 和是否为管理员的信息。
     * 该接口有频率限制（60 秒内最多 10 次请求），防止暴力破解。
     * </p>
     *
     * @param userLoginRequest 包含用户名、密码和验证码的登录请求对象
     * @return 包含认证 token 和是否为管理员的结果对象
     */
    @ApiOperationSupport(order = 1, ignoreParameters = {"tongligong-student-cloud-token"})
    @Operation(summary = "登录")
    @ApiLimit(timeout = 60, maxCount = 10)
    public AjaxJson<LoginResult> doLogin(@Valid @RequestBody UserLoginRequest userLoginRequest) {
        User user = userService.getByLoginIdentifier(userLoginRequest.getUsername());
        if (user == null) {
            throw new BizException(ErrorCode.BIZ_LOGIN_ERROR);
        }
        // All existing verifiers must see the real username, including administrator 2FA.
        userLoginRequest.setUsername(user.getUsername());
        userLoginRequest.setResolvedUserId(user.getId());
        // 进行登录验证，如果验证失败，会抛出异常
        loginService.verify(userLoginRequest);

        // 获取用户的上下文信息, 并登录
        user = userService.requireLoginUser(user.getId());
        if (!PasswordCredentialFingerprint.matches(userLoginRequest.getVerifiedCredentialFingerprint(), user)) {
            throw new BizException(ErrorCode.BIZ_LOGIN_ERROR);
        }
        Integer userId = user.getId();
        StpUtil.login(userId);
        StpUtil.getTokenSession().set(PasswordCredentialFingerprint.SESSION_KEY,
                userLoginRequest.getVerifiedCredentialFingerprint());

        // 返回登录结果
        LoginResult loginResult = new LoginResult(StpUtil.getTokenInfo().getTokenValue(), user);
        return AjaxJson.getSuccess("登录成功", loginResult);
    }

    /**
     * 用户注销
     * <p>
     * 使用 Sa-Token 框架登出当前用户，清除登录会话。
     * </p>
     *
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "注销")
    @PostMapping("/logout")
    public AjaxJson<Void> logout() {
        StpUtil.logout();
        return AjaxJson.getSuccess("注销成功");
    }

    /**
     * 获取登录验证方式
     * <p>
     * 根据系统配置判断当前用户登录时需要的验证方式：
     * 1. 如果开启了图形验证码，返回图形验证码模式
     * 2. 如果用户是管理员且开启了两步验证且已配置验证密钥，返回两步验证模式
     * 3. 否则返回无需验证模式
     * </p>
     *
     * @param username 用户名，用于判断是否为管理员
     * @return 当前适用的登录验证方式枚举值
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "获取登陆验证方式")
    @GetMapping("/login/verify-mode")
    public AjaxJson<LoginVerifyModeEnum> loginVerifyMode(String username) {
        LoginVerifyModeEnum loginVerifyModeEnum = LoginVerifyModeEnum.OFF_MODE;

        // 判断是否开启图形验证码
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        Boolean loginImgVerify = systemConfig.getLoginImgVerify();
        if (BooleanUtils.isTrue(loginImgVerify)) {
            loginVerifyModeEnum = LoginVerifyModeEnum.IMG_VERIFY_MODE;
        }

        // 判断是否是管理员, 并且开启了二次验证
        User user = userService.getByLoginIdentifier(username);
        boolean isAdmin = user != null && userService.isAdmin(user.getId());
        boolean enableTwoFactorAuth = BooleanUtils.isTrue(systemConfig.getAdminTwoFactorVerify());
        boolean loginVerifySecretNotBlank = StringUtils.isNotBlank(systemConfig.getLoginVerifySecret());
        if (isAdmin && enableTwoFactorAuth && loginVerifySecretNotBlank) {
            loginVerifyModeEnum = LoginVerifyModeEnum.TWO_FACTOR_AUTHENTICATION_MODE;
        }
        return AjaxJson.getSuccessData(loginVerifyModeEnum);
    }


    /**
     * 获取图形验证码
     * <p>
     * 生成一个新的图形验证码，返回验证码图片的 Base64 编码和验证码唯一标识 key。
     * 用于登录时的人机验证，防止自动化攻击。
     * </p>
     *
     * @return 包含验证码图片 Base64 和验证码 key 的结果对象
     */
    @ApiOperationSupport(order = 4)
    @Operation(summary = "获取图形验证码")
    @GetMapping("/login/captcha")
    public AjaxJson<LoginVerifyImgResult> captcha() {
        LoginVerifyImgResult loginVerifyImgResult = imgVerifyCodeService.generatorCaptcha();
        return AjaxJson.getSuccessData(loginVerifyImgResult);
    }


    /**
     * 检测当前用户是否已登录
     * <p>
     * 检查当前会话是否处于登录状态。如果已登录，还返回用户是否为管理员、
     * 昵称和用户名等附加信息。
     * </p>
     *
     * @return 包含登录状态、是否为管理员、昵称和用户名的结果对象
     */
    @ApiOperationSupport(order = 5)
    @Operation(summary = "检测是否已登录")
    @GetMapping("/login/check")
    public AjaxJson<CheckLoginResult> checkLogin() {
        CheckLoginResult checkLoginResult = new CheckLoginResult();
        checkLoginResult.setIsLogin(StpUtil.isLogin());
        checkLoginResult.setIsAdmin(false);
        if (checkLoginResult.getIsLogin()) {
            checkLoginResult.setIsAdmin(StpUtil.hasRole("admin"));
            User currentUser = TlgxyCloudAuthUtil.getCurrentUser();
            if (currentUser != null) {
                checkLoginResult.setNickname(currentUser.getNickname());
                checkLoginResult.setUsername(currentUser.getUsername());
                checkLoginResult.setAccountId(currentUser.getAccountId());
                checkLoginResult.setEmail(currentUser.getEmail());
                checkLoginResult.setRole(currentUser.getRole());
                checkLoginResult.setTenantId(currentUser.getTenantId());
            }
        }

        return AjaxJson.getSuccessData(checkLoginResult);
    }

    /**
     * 修改当前用户的密码
     * <p>
     * 需要用户已登录（@SaCheckLogin 注解确保）。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param updateUserPwdRequest 包含旧密码和新密码的请求对象
     * @return 操作成功的 AjaxJson 对象
     */
    @SaCheckLogin
    @ApiOperationSupport(order = 6)
    @PostMapping("/updatePwd")
    @Operation(summary = "修改用户密码")
    @ResponseBody
    @DemoDisable
    public AjaxJson<Void> updatePwd(@RequestBody @Valid UpdateUserPwdRequest updateUserPwdRequest) {
        userService.updateUserNameAndPwdById(TlgxyCloudAuthUtil.getCurrentUserId(), updateUserPwdRequest);
        return AjaxJson.getSuccess();
    }

    /**
     * 重置管理员密码（仅调试模式可用）
     * <p>
     * 仅在调试模式（debug=true）下可用，用于强制将管理员账户的用户名和密码修改为指定值。
     * 同时重置登录验证模式相关的配置。非调试模式下访问将抛出业务异常。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param requestObj 包含新的管理员用户名和密码的请求对象
     * @return 操作成功的 AjaxJson 对象
     */
    @ResponseBody
    @ApiOperationSupport(order = 7)
    @Operation(summary = "重置管理员密码", description = "开启 debug 模式时，访问此接口会强制将管理员账户密码修改为用户指定值")
    @PutMapping("/resetAdminPassword")
    @DemoDisable
    public AjaxJson<Void> resetPwd(@RequestBody @Valid ResetAdminUserNameAndPasswordRequest requestObj) {
        if (!tlgxyCloudProperties.isDebug()) {
            log.warn("当前为非调试模式, 无法重置管理员登录信息");
            throw new BizException(ErrorCode.BIZ_ERROR);
        }
        userService.resetAdminLoginInfo(requestObj);
        systemConfigService.resetLoginVerifyMode();
        return AjaxJson.getSuccess();
    }

}
