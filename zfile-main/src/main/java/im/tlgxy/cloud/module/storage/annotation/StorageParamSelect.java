package im.tlgxy.cloud.module.storage.annotation;

import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceParamDef;

import java.util.List;

/**
 * 存储源参数动态下拉选项接口。
 *
 * 当 {@link StorageParamItem} 注解的 type 属性设置为 SELECT（下拉选择）类型，
 * 且下拉选项的值无法在注解中静态定义时（需要从外部动态获取），
 * 可以通过实现此接口来提供动态的下拉选项数据。
 *
 * 典型应用场景：
 * - 编码格式选择（{@link im.tlgxy.cloud.module.storage.annotation.impl.EncodingStorageParamSelect}）
 * - 存储桶列表选择（从云服务 API 动态获取）
 * - 地域列表选择
 *
 * 使用方式：在 {@link StorageParamItem#optionsClass()} 中指定实现此接口的类。
 *
 * @author zhaojun
 */
public interface StorageParamSelect {

	/**
	 * 获取存储源参数的可选下拉选项列表。
	 *
	 * 框架在渲染存储源配置表单时，如果发现某个参数配置了此接口的实现类，
	 * 会调用此方法来动态获取下拉选项列表，供用户选择。
	 *
	 * @param   storageParamItem
	 *          当前的存储源参数注解实例，包含了该参数的所有元数据信息（如 key、name、required 等）
	 *
	 * @param   targetParam
	 *          当前正在配置的存储源参数对象，可通过此对象获取用户已填写或已保存的参数值
	 *
	 * @return  下拉选项列表，每个选项包含 label（显示值）和 value（存储值）
	 */
	List<StorageSourceParamDef.Options> getOptions(StorageParamItem storageParamItem, IStorageParam targetParam);

}
