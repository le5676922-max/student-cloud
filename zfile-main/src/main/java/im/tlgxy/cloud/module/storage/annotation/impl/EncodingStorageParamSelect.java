package im.tlgxy.cloud.module.storage.annotation.impl;

import im.tlgxy.cloud.module.storage.annotation.StorageParamItem;
import im.tlgxy.cloud.module.storage.annotation.StorageParamSelect;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceParamDef;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 编码格式动态下拉选项的实现类。
 *
 * 实现了 {@link StorageParamSelect} 接口，通过 JVM 的 {@link Charset#availableCharsets()} 方法
 * 动态获取当前 Java 环境支持的所有字符编码格式，并将其作为存储源参数的下拉选项。
 *
 * 使用场景：当存储源需要配置文件的编码格式（如 UTF-8、GBK、ISO-8859-1 等）时，
 * 通过在 {@link StorageParamItem#optionsClass()} 中指定此类，前端下拉框将自动列出所有可用编码格式。
 *
 * @author zhaojun
 */
public class EncodingStorageParamSelect implements StorageParamSelect {

	/**
	 * 获取所有可用的字符编码格式作为下拉选项列表。
	 *
	 * 通过 {@link Charset#availableCharsets()} 获取 JVM 当前支持的所有字符集，
	 * 遍历这些字符集的名称，将其包装为选项对象并返回。
	 * 注意：此方法不使用 storageParamItem 和 targetParam 参数，因为编码格式列表与具体参数无关。
	 *
	 * @param   storageParamItem
	 *          存储源参数注解实例（本实现中未使用此参数）
	 *
	 * @param   targetParam
	 *          存储源参数对象（本实现中未使用此参数）
	 *
	 * @return  包含所有可用字符编码格式名称的选项列表，每个选项的 label 和 value 均为编码名称（如 "UTF-8"）
	 */
	@Override
	public List<StorageSourceParamDef.Options> getOptions(StorageParamItem storageParamItem, IStorageParam targetParam) {
		List<StorageSourceParamDef.Options> options = new ArrayList<>();

		// 遍历 JVM 支持的所有字符集，将每个字符集的名称作为下拉选项
		for (String name : Charset.availableCharsets().keySet()) {
			StorageSourceParamDef.Options option = new StorageSourceParamDef.Options(name);
			options.add(option);
		}
		return options;
	}

}
