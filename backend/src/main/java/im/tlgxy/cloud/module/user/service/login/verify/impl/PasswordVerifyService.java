package im.tlgxy.cloud.module.user.service.login.verify.impl;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.login.verify.LoginVerifyService;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import im.tlgxy.cloud.module.user.utils.PasswordCredentialFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Objects;

@Slf4j
@Service
@Order(3)
public class PasswordVerifyService implements LoginVerifyService {

    @Resource
    private UserService userService;

    @Override
    public void verify(UserLoginRequest userLoginRequest) {
        User dbUser = userLoginRequest.getResolvedUserId() == null
                ? userService.getByUsername(userLoginRequest.getUsername())
                : userService.requireLoginUser(userLoginRequest.getResolvedUserId());
        if (dbUser == null) {
            throw new BizException(ErrorCode.BIZ_LOGIN_ERROR);
        }

        String dbPassword = dbUser.getPassword();
        String dbSalt = dbUser.getSalt();
        String requestPassword = userLoginRequest.getPassword();

        if (!PasswordVerifyUtils.verify(dbPassword, dbSalt, requestPassword)) {
            throw new BizException(ErrorCode.BIZ_LOGIN_ERROR);
        }
        userService.requireLoginUser(dbUser.getId());
        userLoginRequest.setVerifiedCredentialFingerprint(PasswordCredentialFingerprint.of(dbUser));
    }

}
