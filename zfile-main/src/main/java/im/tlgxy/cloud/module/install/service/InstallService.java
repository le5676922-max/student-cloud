package im.tlgxy.cloud.module.install.service;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.install.model.request.InstallSystemRequest;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.user.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统安装服务
 * <p>
 * 负责处理系统首次安装（初始化）的核心业务逻辑，包括：
 * <ul>
 *   <li>创建超级管理员账户</li>
 *   <li>设置站点基本配置（站点名称、安装状态等）</li>
 *   <li>检查系统是否已完成初始化，防止重复初始化</li>
 * </ul>
 * <p>
 * 初始化过程中使用事务管理，确保数据一致性。如果系统已初始化则抛出业务异常。
 *
 * @author zhaojun
 */
@Service
@Slf4j
public class InstallService {

    /**
     * 系统配置服务，用于读写系统配置信息
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 用户服务，用于创建管理员用户
     */
    @Resource
    private UserService userService;

    /**
     * 执行系统初始化
     * <p>
     * 该方法在事务中执行以下操作：
     * <ol>
     *   <li>检查系统是否已初始化，如果已初始化则抛出 {@link BizException} 异常</li>
     *   <li>创建管理员用户，如果创建失败则抛出 {@link SystemException} 异常</li>
     *   <li>更新系统配置，设置站点名称和安装状态为已安装</li>
     * </ol>
     *
     * @param installSystemRequest 初始化请求参数，包含站点名称、管理员用户名和密码
     * @throws BizException    如果系统已经初始化
     * @throws SystemException 如果管理员用户创建失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void install(InstallSystemRequest installSystemRequest) {
        // 检查系统是否已初始化，防止重复初始化
        if (getSystemIsInstalled()) {
            throw new BizException(ErrorCode.BIZ_SYSTEM_ALREADY_INIT);
        }

        // 初始化管理员用户
        boolean updateFlag = userService.initAdminUser(installSystemRequest.getUsername(),
                installSystemRequest.getPassword());
        if (!updateFlag) {
            throw new SystemException(ErrorCode.BIZ_SYSTEM_INIT_ERROR);
        }

        // 设置系统配置：站点名称和安装状态
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        systemConfigDTO.setSiteName(installSystemRequest.getSiteName());
        systemConfigDTO.setInstalled(true);
        systemConfigService.updateSystemConfig(systemConfigDTO);
    }

    /**
     * 获取系统是否已初始化
     * <p>
     * 通过查询系统配置中的 installed 字段来判断系统是否已完成初始化流程。
     *
     * @return true 表示系统已初始化，false 表示系统尚未初始化
     */
    public Boolean getSystemIsInstalled() {
        return systemConfigService.getSystemConfig().getInstalled();
    }

}