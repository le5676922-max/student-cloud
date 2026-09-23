package im.tlgxy.cloud.module.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.log.model.entity.DownloadLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 下载日志 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供基础的 CRUD 操作。
 * 并额外定义了两个自定义方法：根据存储源 key 删除日志和删除过期的短链下载日志。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface DownloadLogMapper extends BaseMapper<DownloadLog> {

	/**
	 * 根据存储源 KEY 删除所有相关的下载日志数据
	 * <p>
	 * 通常在存储源被删除时调用，用于清理关联的下载日志记录。
	 * </p>
	 *
	 * @param 	storageKey
	 * 			存储源 KEY
	 * @return  删除的记录数
	 */
	int deleteByStorageKey(String storageKey);


	/**
	 * 删除过期的短链下载日志
	 * <p>
	 * 定期清理已过期的短链关联的下载日志，释放数据库空间。
	 * </p>
	 *
	 * @return	删除的行数
	 */
	int deleteExpireShortLinkLog();

}