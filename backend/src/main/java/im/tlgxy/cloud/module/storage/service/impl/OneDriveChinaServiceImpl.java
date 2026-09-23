package im.tlgxy.cloud.module.storage.service.impl;

import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.OneDriveChinaParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractOneDriveServiceBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author zhaojun
 */
@Service
@Slf4j
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class OneDriveChinaServiceImpl extends AbstractOneDriveServiceBase<OneDriveChinaParam> {

    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.ONE_DRIVE_CHINA;
    }

    @Override
    public String getGraphEndPoint() {
        return "microsoftgraph.chinacloudapi.cn";
    }

    @Override
    public String getAuthenticateEndPoint() {
        return "login.partner.microsoftonline.cn";
    }
    
    @Override
    public String getClientId() {
        if (param == null || param.getClientId() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getClientId();
        }
        return param.getClientId();
    }
    
    @Override
    public String getRedirectUri() {
        if (param == null || param.getRedirectUri() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getRedirectUri();
        }
        return param.getRedirectUri();
    }
    
    @Override
    public String getClientSecret() {
        if (param == null || param.getClientSecret() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getClientSecret();
        }
        return param.getClientSecret();
    }
    
    @Override
    public String getScope() {
        return tlgxyCloudProperties.getOnedriveChina().getScope();
    }

}