package im.tlgxy.cloud.module.storage.function;

import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：搜索功能 —— 根据存储源搜索配置决定权限
 * <p>
 * 实现 {@link Function} 接口，根据存储源的 searchEnable（是否启用搜索）配置来决定
 * 搜索相关文件操作的权限默认值。搜索功能仅对管理员开放，匿名用户不提供搜索权限。
 * </p>
 *
 * @author zhaojun
 */
public class SearchFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/** 存储源服务，用于根据 ID 查询存储源的搜索配置 */
	private StorageSourceService storageSourceService;

	/**
	 * 根据存储源 ID 查询搜索开关配置，返回搜索功能的权限默认值
	 * <p>
	 * 权限规则：
	 * <ul>
	 *   <li>管理员权限：取决于存储源的 searchEnable 配置</li>
	 *   <li>匿名用户权限：始终为 false（搜索功能不对匿名用户开放）</li>
	 * </ul>
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID
	 *
	 * @return	文件操作类型默认值 DTO，仅管理员可能有权搜索，匿名用户始终无权
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		// 懒加载获取 StorageSourceService Bean
		if (storageSourceService == null) {
			storageSourceService = SpringUtil.getBean(StorageSourceService.class);
		}

		// 查询存储源的搜索开关配置
		StorageSource storageSource = storageSourceService.findById(storageId);
		// 搜索功能仅限管理员使用，匿名用户始终无权搜索
		return new FileOperatorTypeDefaultValueDTO(storageSource.getSearchEnable(), false);
	}

}