package im.tlgxy.cloud.module.permission.convert;

import im.tlgxy.cloud.module.permission.model.entity.PermissionConfig;
import im.tlgxy.cloud.module.permission.model.result.PermissionConfigResult;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限配置实体类转换器
 * <p>
 * 使用 MapStruct 实现 PermissionConfig 实体列表到 PermissionConfigResult 结果对象列表的转换。
 * 配置了 unmappedTargetPolicy = IGNORE，忽略未映射的目标字段（如 operatorName 和 tips 由 Controller 层手动填充）。
 * </p>
 *
 * @author zhaojun
 */
@Component
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PermissionConfigConvert {

	/**
	 * 将权限配置实体列表转换为权限配置结果列表
	 *
	 * @param permissionConfig 权限配置实体列表
	 * @return 权限配置结果列表
	 */
	List<PermissionConfigResult> toResult(List<PermissionConfig> permissionConfig);

}