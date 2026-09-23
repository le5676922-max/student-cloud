package im.tlgxy.cloud.module.storage.function;

import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：允许所有用户操作
 * <p>
 * 实现 {@link Function} 接口，根据存储源 ID 返回文件操作权限的默认值。
 * 此策略表示：管理员和匿名用户都拥有所有文件操作权限，即完全开放。
 * 通常用于公共网盘等不需要权限控制的场景。
 * </p>
 *
 * @author zhaojun
 */
public class AllowAllFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/**
	 * 根据存储源 ID 返回允许所有用户操作的权限配置
	 * <p>
	 * 返回的 DTO 中：
	 * <ul>
	 *   <li>管理员权限：true（允许）</li>
	 *   <li>匿名用户权限：true（允许）</li>
	 * </ul>
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID（本策略中不使用此参数，但为了接口统一保留）
	 *
	 * @return	文件操作类型默认值 DTO，管理员和匿名用户均可使用
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		return new FileOperatorTypeDefaultValueDTO(true, true);
	}

}