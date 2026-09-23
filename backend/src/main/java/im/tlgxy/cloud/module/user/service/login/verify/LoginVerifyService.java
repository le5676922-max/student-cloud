package im.tlgxy.cloud.module.user.service.login.verify;

import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;

public interface LoginVerifyService {

    void verify(UserLoginRequest userLoginRequest);

}
