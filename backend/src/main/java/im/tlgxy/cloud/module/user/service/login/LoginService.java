package im.tlgxy.cloud.module.user.service.login;

import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.login.verify.LoginVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class LoginService {

    @Resource
    private List<LoginVerifyService> loginVerifyServiceList;

    public void verify(UserLoginRequest userLoginRequest) {
        loginVerifyServiceList.forEach(loginVerifyService -> {
            loginVerifyService.verify(userLoginRequest);
        });
    }


}
