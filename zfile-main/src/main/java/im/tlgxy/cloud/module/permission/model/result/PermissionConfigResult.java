package im.tlgxy.cloud.module.permission.model.result;

import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import lombok.Data;

/**
 * 权限配置结果类
 * <p>
 * 用于前端展示的权限配置数据传输对象。
 * 相比 PermissionConfig 实体，额外包含了操作名称（operatorName）和提示信息（tips），
 * 这两个字段由 Controller 层从枚举中获取后手动填充。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class PermissionConfigResult {

	/**
	 * 操作类型枚举
	 */
	private FileOperatorTypeEnum operator;

	/**
	 * 是否允许管理员执行此操作
	 */
	private Boolean allowAdmin;

	/**
	 * 是否允许匿名用户执行此操作
	 */
	private Boolean allowAnonymous;

	/**
	 * 操作名称（由枚举的 getName() 获取，Controller 层填充）
	 */
	private String operatorName;

	/**
	 * 提示信息（由枚举的 getTips() 获取，Controller 层填充）
	 */
	private String tips;

}