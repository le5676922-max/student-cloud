package im.tlgxy.cloud.module.storage.function;

import org.apache.commons.lang3.BooleanUtils;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：基础策略 —— 根据存储源配置决定权限
 * <p>
 * 实现 {@link Function} 接口，根据存储源（StorageSource）的配置项来决定文件操作权限的默认值。
 * 这是最常用的权限策略，通过读取存储源实体中的 enableFileOperator（是否启用文件操作）
 * 和 enableFileAnnoOperator（是否允许匿名文件操作）两个字段来动态计算权限。
 * </p>
 * <p>
 * 逻辑规则：
 * <ul>
 *   <li>如果存储源启用了文件操作，则管理员有权限</li>
 *   <li>如果存储源启用了文件操作且同时启用了匿名文件操作，则匿名用户也有权限</li>
 * </ul>
 * </p>
 *
 * @see FileOperatorTypeEnum#NEW_FOLDER
 * @see FileOperatorTypeEnum#DELETE
 * ...
 *
 * @author zhaojun
 */
public class BasicFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/** 存储源服务，使用懒加载方式获取 Spring Bean 避免循环依赖 */
	private static StorageSourceService storageSourceService;

	/**
	 * 根据存储源 ID 查询存储源配置，动态返回文件操作权限默认值
	 * <p>
	 * 通过存储源的 enableFileOperator（是否启用文件操作）和
	 * enableFileAnnoOperator（是否允许匿名操作）两个字段决定权限。
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID，用于查询对应的存储源配置
	 *
	 * @return	文件操作类型默认值 DTO，包含管理员和匿名用户的权限配置
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		// 懒加载获取 StorageSourceService Bean，避免与 Spring 容器初始化产生循环依赖
		if (storageSourceService == null) {
			storageSourceService = SpringUtil.getBean(StorageSourceService.class);
		}
		// 根据存储源 ID 查询存储源配置信息
		StorageSource storageSource = storageSourceService.findById(storageId);
		// 安全地将 Boolean 类型转换为基本类型，null 值时默认为 false
		Boolean enableFileOperator = BooleanUtils.isTrue(storageSource.getEnableFileOperator());
		Boolean enableFileAnnoOperator = BooleanUtils.isTrue(storageSource.getEnableFileAnnoOperator());

		// 管理员权限：只要存储源启用了文件操作，管理员即可操作
		boolean allowAdmin = enableFileOperator;
		// 匿名用户权限：需要同时满足启用文件操作和允许匿名操作
		boolean allowAnonymous = enableFileOperator && enableFileAnnoOperator;
		return new FileOperatorTypeDefaultValueDTO(allowAdmin, allowAnonymous);
	}


}