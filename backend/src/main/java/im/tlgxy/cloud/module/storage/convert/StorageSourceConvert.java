package im.tlgxy.cloud.module.storage.convert;

import im.tlgxy.cloud.module.readme.model.entity.ReadmeConfig;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceAdminResult;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceDTO;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceConfigResult;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceResult;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存储源对象转换器接口
 * <p>
 * 使用 MapStruct 框架实现存储源相关实体、DTO、请求和返回结果之间的对象映射转换。
 * 负责存储源（StorageSource）实体与各类 DTO/Result 之间的自动转换，减少手动 setter/getter 调用。
 * </p>
 * <p>
 * 转换配置说明：
 * <ul>
 *   <li>componentModel = "spring"：将生成的实现类注册为 Spring Bean，方便注入使用</li>
 *   <li>unmappedTargetPolicy = IGNORE：忽略未映射的目标属性，避免编译时报错</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Component
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface StorageSourceConvert {


	/**
	 * 将存储源实体列表转换为前端展示结果列表
	 * <p>
	 * 用于前端浏览页面展示存储源信息时，将数据库实体集合转换为前端需要的轻量级展示对象。
	 * </p>
	 *
	 * @param   list
	 *          存储源实体列表
	 *
	 * @return  存储源结果对象列表，供前端展示使用
	 */
	List<StorageSourceResult> entityToResultList(List<StorageSource> list);


	/**
	 * 将存储源实体转换为存储源配置结果对象
	 * <p>
	 * 在构建存储源配置信息时，将存储源实体与对应的 README 配置合并转换为配置结果对象。
	 * 通过 @Mapping 注解将 readmeConfig.displayMode 映射到目标对象的 readmeDisplayMode 字段。
	 * </p>
	 *
	 * @param   storageSource
	 *          存储源实体
	 * @param   readmeConfig
	 *          README 配置实体
	 *
	 * @return  存储源配置结果对象
	 */
	@Mapping(source = "readmeConfig.displayMode", target = "readmeDisplayMode")
	StorageSourceConfigResult entityToConfigResult(StorageSource storageSource, ReadmeConfig readmeConfig);


	/**
	 * 将存储源实体列表转换为后台管理结果列表
	 * <p>
	 * 用于后台管理页面展示所有存储源时，将数据库实体集合转换为管理后台需要的展示对象。
	 * </p>
	 *
	 * @param   list
	 *          存储源实体列表
	 *
	 * @return  存储源管理后台结果对象列表
	 */
	List<StorageSourceAdminResult> entityToAdminResultList(List<StorageSource> list);


	/**
	 * 将存储源实体和完整参数信息转换为 DTO 对象
	 * <p>
	 * 合并存储源基本信息与完整的参数配置信息，生成包含所有参数的存储源 DTO 对象。
	 * </p>
	 *
	 * @param   storageSource
	 *          存储源实体
	 * @param   storageSourceAllParam
	 *          存储源完整参数信息
	 *
	 * @return  包含全部参数信息的存储源 DTO 对象
	 */
	StorageSourceDTO entityToDTO(StorageSource storageSource, StorageSourceAllParamDTO storageSourceAllParam);


	/**
	 * 将保存存储源的请求对象转换为存储源实体
	 * <p>
	 * 在新增或更新存储源时，将前端传来的请求参数对象转换为可直接持久化的数据库实体。
	 * </p>
	 *
	 * @param   saveStorageSourceRequest
	 *          保存存储源的请求对象，包含前端提交的存储源配置信息
	 *
	 * @return  存储源实体对象，用于数据库持久化操作
	 */
	StorageSource saveRequestToEntity(SaveStorageSourceRequest saveStorageSourceRequest);

}