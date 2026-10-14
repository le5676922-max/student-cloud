package im.tlgxy.cloud.module.user.service.login.verify.impl;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.login.ImgVerifyCodeService;
import im.tlgxy.cloud.module.user.service.login.verify.LoginVerifyService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Order(1)
public class ImgCodeLoginVerifyService implements LoginVerifyService {

    @Resource
    private ImgVerifyCodeService imgVerifyCodeService;

    @Resource
    private SystemConfigService systemConfigService;

    @Resource
    private UserService userService;

    @Override
    public void verify(UserLoginRequest userLoginRequest) {
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        if (BooleanUtils.isNotTrue(systemConfig.getLoginImgVerify())) {
            return;
        }

        // 如果是管理员, 且开启了管理员二次验证, 则不需要进行图片验证码验证
        boolean isAdmin = userLoginRequest.getResolvedUserId() == null
                ? userService.isAdmin(userLoginRequest.getUsername())
                : userService.isAdmin(userLoginRequest.getResolvedUserId());
        boolean enable2FA = BooleanUtils.isTrue(systemConfig.getAdminTwoFactorVerify()) && StringUtils.isNotBlank(systemConfig.getLoginVerifySecret());
        if (isAdmin && enable2FA) {
            return;
        }

        String verifyCode = userLoginRequest.getVerifyCode();
        String verifyCodeUuid = userLoginRequest.getVerifyCodeUUID();
        imgVerifyCodeService.checkCaptcha(verifyCodeUuid, verifyCode);
    }

}
