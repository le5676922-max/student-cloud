package im.tlgxy.cloud.module.user.service;

import cn.hutool.core.lang.Validator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.WorkspaceEnrollmentService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.PublicRegistrationRequest;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PublicRegistrationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserMapper users;
    private final EmailVerificationService emailVerification;
    private final WorkspaceEnrollmentService enrollment;

    public record Config(boolean enabled, boolean emailVerificationRequired, boolean mailAvailable,
                         boolean memberNameJoinRequiresApproval) { }
    public record Result(String accountId, String email, String role, Integer tenantId, String tenantName,
                         String status, String message) { }

    public Config config() { return new Config(true, true, emailVerification.isAvailable(), true); }

    public void sendEmailCode(String rawEmail) {
        String email = normalizedEmail(rawEmail);
        ensureEmailAvailable(email);
        emailVerification.sendCode(email, "REGISTER");
    }

    public Result register(PublicRegistrationRequest request) {
        String email = normalizedEmail(request.email());
        if (request.password() == null || request.password().isBlank() || request.password().length() < 8
                || request.password().length() > 128 || !Objects.equals(request.password(), request.confirmPassword()))
            throw new BizException("密码需为8至128个字符，且两次输入必须一致");
        if (!"TENANT_ADMIN".equals(request.registrationType()) && !"USER".equals(request.registrationType()))
            throw new BizException("注册类型只允许工作空间管理员或普通成员");
        if (request.nickname() != null && request.nickname().length() > 100) throw new BizException("昵称不能超过100个字符");
        if ("TENANT_ADMIN".equals(request.registrationType())) WorkspaceEnrollmentService.requireName(request.workspaceName());
        else {
            if (!"INVITE".equals(request.joinType()) && !"NAME".equals(request.joinType())) throw new BizException("请选择加入方式");
            WorkspaceEnrollmentService.requireName(request.joinValue());
        }
        ensureEmailAvailable(email);
        emailVerification.consumeCode(email, "REGISTER", request.emailCode());
        User user = new User();
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String accountId = "SC" + HexFormat.of().withUpperCase().formatHex(bytes);
        user.setAccountId(accountId);
        user.setUsername(accountId);
        user.setEmail(email);
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        user.setNickname(nickname.isEmpty() ? accountId : nickname);
        var password = PasswordVerifyUtils.encrypt(request.password());
        user.setPassword(password.getFirst());
        user.setSalt(password.getSecond());
        try {
            WorkspaceEnrollmentService.Enrollment joined = "TENANT_ADMIN".equals(request.registrationType())
                    ? enrollment.createWorkspace(user, request.workspaceName())
                    : enrollment.joinWorkspace(user, request.joinType(), request.joinValue());
            boolean pending = "PENDING".equals(joined.status());
            return new Result(accountId, email, user.getRole().name(), joined.tenant().getId(), joined.tenant().getName(),
                    joined.status(), pending ? "注册成功，等待工作空间管理员审核后即可登录" : "注册成功，请使用邮箱或系统账号登录");
        } catch (DuplicateKeyException conflict) {
            throw new BizException("邮箱、工作空间名称或系统账号已存在，请检查后重试");
        }
    }

    private void ensureEmailAvailable(String email) {
        if (users.selectCount(Wrappers.<User>lambdaQuery().eq(User::getEmail, email)
                .or().apply("LOWER(username) = {0}", email).or().eq(User::getAccountId, email)) > 0)
            throw new BizException("邮箱已被使用，请直接登录或使用其他邮箱");
    }

    static String normalizedEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !Validator.isEmail(email)) throw new BizException("请输入有效邮箱，长度不能超过254个字符");
        return email;
    }
}
