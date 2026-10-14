package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.PasswordResetRequest;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Service
public class PasswordResetService {
    public static final String SENT_MESSAGE = "验证码已发送，请查收邮箱；仅已绑定此邮箱的可用账号可以重置密码";
    public static final String RESET_MESSAGE = "验证已完成；若该邮箱绑定了可用账号，密码已重置，请重新登录";
    private final UserMapper users;
    private final UserService userService;
    private final EmailVerificationService verification;
    private final PasswordResetSessionInvalidator sessions;
    private final TransactionTemplate transactions;

    public PasswordResetService(UserMapper users, UserService userService, EmailVerificationService verification,
                                PasswordResetSessionInvalidator sessions, PlatformTransactionManager manager) {
        this.users = users;
        this.userService = userService;
        this.verification = verification;
        this.sessions = sessions;
        this.transactions = new TransactionTemplate(manager);
    }

    public record Config(boolean enabled, boolean mailAvailable) { }

    public Config config() { return new Config(true, verification.isAvailable()); }

    public void sendEmailCode(String rawEmail) {
        // Use the same delivery and response for every valid address. No user lookup before SMTP:
        // neither response wording nor SMTP timing reveals whether an account exists or is disabled.
        verification.sendCode(PublicRegistrationService.normalizedEmail(rawEmail), EmailVerificationService.PASSWORD_RESET);
    }

    public void reset(PasswordResetRequest request) {
        String email = PublicRegistrationService.normalizedEmail(request.email());
        if (request.password() == null || request.password().isBlank() || request.password().length() < 8
                || request.password().length() > 128 || !Objects.equals(request.password(), request.confirmPassword()))
            throw new BizException("密码需为8至128个字符，且两次输入必须一致");
        // Independently commit attempts and consumption so account-write failures cannot replay a code.
        verification.consumeCode(email, EmailVerificationService.PASSWORD_RESET, request.emailCode());
        // Hash even when the account is unknown: a verified address still gets the same generic result.
        var encrypted = PasswordVerifyUtils.encrypt(request.password());
        Integer changedUser = transactions.execute(status -> {
            List<User> matches = users.selectList(Wrappers.<User>lambdaQuery().eq(User::getEmail, email).last("LIMIT 2"));
            if (matches.size() != 1) return null;
            User user = matches.getFirst();
            if (Objects.equals(user.getId(), UserConstant.NEW_USER_TEMPLATE_ID)
                    || Objects.equals(user.getId(), UserConstant.ANONYMOUS_ID)
                    || !Boolean.TRUE.equals(user.getEnable()) || user.getRole() == null || !user.getRole().isLoginRole())
                return null;
            try {
                userService.requireLoginUser(user.getId());
            } catch (BizException unavailable) {
                // Disabled workspace or unapproved membership must not become a recovery bypass.
                return null;
            }
            int changed = users.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, user.getId()).eq(User::getEmail, email).eq(User::getEnable, true)
                    .eq(User::getPassword, user.getPassword()).eq(User::getSalt, user.getSalt())
                    .set(User::getPassword, encrypted.getFirst()).set(User::getSalt, encrypted.getSecond()));
            if (changed != 1) throw new BizException("账号状态已发生变化，请重新获取验证码后重试");
            return user.getId();
        });
        if (changedUser != null) sessions.invalidate(changedUser);
    }
}
