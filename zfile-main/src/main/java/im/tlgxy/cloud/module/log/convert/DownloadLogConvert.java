package im.tlgxy.cloud.module.log.convert;

import im.tlgxy.cloud.module.log.model.entity.DownloadLog;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.log.model.result.DownloadLogResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.stereotype.Component;

/**
 * 下载日志实体类转换器
 * <p>
 * 使用 MapStruct 实现 DownloadLog 实体和 StorageSource 实体到 DownloadLogResult 结果对象的转换。
 * 主要作用是将下载日志与存储源信息合并为一个完整的结果对象，供前端展示使用。
 * </p>
 *
 * @author zhaojun
 */
@Component
@Mapper(componentModel = "spring")
public interface DownloadLogConvert {

	/**
	 * 将下载日志实体和存储源实体转换为下载日志结果对象
	 *
	 * @param downloadLog   下载日志实体，提供日志基本字段（ID、路径、IP 等）
	 * @param storageSource 存储源实体，提供存储源名称和类型字段
	 * @return 合并后的下载日志结果对象
	 */
	@Mapping(source = "downloadLog.id", target = "id")
	@Mapping(source = "storageSource.name", target = "storageName")
	@Mapping(source = "storageSource.type", target = "storageType")
	DownloadLogResult entityToResultList(DownloadLog downloadLog, StorageSource storageSource);

}