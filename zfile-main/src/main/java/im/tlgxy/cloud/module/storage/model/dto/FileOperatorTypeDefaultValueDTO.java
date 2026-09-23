package im.tlgxy.cloud.module.storage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 文件操作类型默认值数据传输对象（DTO）
 * <p>
 * 封装存储源各类文件操作权限的默认值，用于文件操作权限策略函数（{@code Function<Integer, FileOperatorTypeDefaultValueDTO>}）
 * 的返回值。该对象描述某个文件操作类型（如新建文件夹、删除、重命名、搜索等）对管理员和匿名用户的默认启用状态。
 * </p>
 * <p>
 * 在初始化存储源的权限配置时，系统遍历所有文件操作类型，调用对应的策略函数获取此 DTO，
 * 然后根据 DTO 中的值设置各项操作对管理员和匿名用户的默认开关状态。
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
public class FileOperatorTypeDefaultValueDTO {

	/** 管理员是否有该操作的权限 */
	private boolean allowAdmin;

	/** 匿名用户是否有该操作的权限 */
	private boolean allowAnonymous;

}