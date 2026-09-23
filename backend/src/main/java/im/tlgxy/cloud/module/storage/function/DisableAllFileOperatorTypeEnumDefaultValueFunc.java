package im.tlgxy.cloud.module.storage.function;

import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：禁用所有操作
 * <p>
 * 实现 {@link Function} 接口，根据存储源 ID 返回文件操作权限的默认值。
 * 此策略表示：管理员和匿名用户都禁止所有文件操作，即完全关闭文件操作功能。
 * 通常用于仅提供文件浏览和下载而不允许任何修改操作的场景。
 * </p>
 *
 * @author zhaojun
 */
public class DisableAllFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/**
	 * 根据存储源 ID 返回禁用所有操作的权限配置
	 * <p>
	 * 返回的 DTO 中：
	 * <ul>
	 *   <li>管理员权限：false（禁止）</li>
	 *   <li>匿名用户权限：false（禁止）</li>
	 * </ul>
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID（本策略中不使用此参数，但为了接口统一保留）
	 *
	 * @return	文件操作类型默认值 DTO，管理员和匿名用户均被禁止
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		return new FileOperatorTypeDefaultValueDTO(false, false);
	}

}