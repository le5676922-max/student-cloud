package im.tlgxy.cloud.module.storage.function;

import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：直链功能 —— 根据全局站点配置决定权限
 * <p>
 * 实现 {@link Function} 接口，根据全局系统配置中的 showPathLink（是否显示直链）设置来决定
 * 直链相关文件操作的权限默认值。直链权限与存储源无关，由系统全局设置统一控制。
 * </p>
 * <p>
 * 权限规则：管理员和匿名用户的直链权限保持一致，都取决于全局的 showPathLink 配置。
 * </p>
 *
 * @author zhaojun
 */
public class LinkFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/** 系统配置服务，使用懒加载方式获取 Spring Bean 避免循环依赖 */
	private static SystemConfigService systemConfigService;

	/**
	 * 根据全局站点设置是否允许使用直链来控制权限
	 * <p>
	 * 管理员和匿名用户的直链权限一致，都取决于系统全局配置中的 showPathLink 设置。
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID（本策略中不使用此参数，因为直链权限由全局配置决定，与具体存储源无关）
	 *
	 * @return	文件操作类型默认值 DTO，管理员和匿名用户权限一致
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		// 懒加载获取 SystemConfigService Bean
		if (systemConfigService == null) {
			systemConfigService = SpringUtil.getBean(SystemConfigService.class);
		}

		// 获取系统全局配置
		SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();

		// 管理员和匿名用户的直链权限都取决于全局配置中的 showPathLink 开关
		Boolean showPathLink = systemConfig.getShowPathLink();
		return new FileOperatorTypeDefaultValueDTO(showPathLink, showPathLink);
	}


}