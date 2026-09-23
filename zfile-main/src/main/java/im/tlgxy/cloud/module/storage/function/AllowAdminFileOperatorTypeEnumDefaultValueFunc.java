package im.tlgxy.cloud.module.storage.function;

import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;

import java.util.function.Function;

/**
 * 文件操作权限默认值策略：仅允许管理员操作
 * <p>
 * 实现 {@link Function} 接口，根据存储源 ID 返回文件操作权限的默认值。
 * 此策略表示：管理员拥有所有文件操作权限，匿名用户没有任何文件操作权限。
 * 通常用于需要登录后才能进行文件操作的存储源类型。
 * </p>
 *
 * @author zhaojun
 */
public class AllowAdminFileOperatorTypeEnumDefaultValueFunc implements Function<Integer, FileOperatorTypeDefaultValueDTO> {

	/**
	 * 根据存储源 ID 返回仅允许管理员操作的权限配置
	 * <p>
	 * 返回的 DTO 中：
	 * <ul>
	 *   <li>管理员权限：true（允许）</li>
	 *   <li>匿名用户权限：false（不允许）</li>
	 * </ul>
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID（本策略中不使用此参数，但为了接口统一保留）
	 *
	 * @return	文件操作类型默认值 DTO，管理员可用，匿名用户不可用
	 */
	@Override
	public FileOperatorTypeDefaultValueDTO apply(Integer storageId) {
		return new FileOperatorTypeDefaultValueDTO(true, false);
	}

}