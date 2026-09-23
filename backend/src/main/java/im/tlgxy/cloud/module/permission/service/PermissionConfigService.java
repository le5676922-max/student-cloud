package im.tlgxy.cloud.module.permission.service;

import im.tlgxy.cloud.module.permission.mapper.PermissionConfigMapper;
import im.tlgxy.cloud.module.permission.model.entity.PermissionConfig;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 存储源权限 Service（已废弃）
 * <p>
 * 封装存储源权限配置的查询和删除业务逻辑。
 * 已标记为 @Deprecated，表示该类功能已由新版本替代，仅保留向后兼容。
 * 提供了线程安全的查询方法（synchronized）和非线程安全的缓存查询方法。
 * 同时监听存储源删除事件，自动清理关联的权限配置。
 * </p>
 *
 * @author zhaojun
 *
 * @deprecated 使用新版本的权限管理替代
 */
@Service
@Slf4j
@CacheConfig(cacheNames = "permissionConfig")
@Deprecated
public class PermissionConfigService {

	/** 权限配置 Mapper */
	@Resource
	private PermissionConfigMapper permissionConfigMapper;

	/**
	 * 根据存储源 ID 查询权限配置（线程安全版本）
	 * <p>
	 * 通过 synchronized 关键字保证线程安全，解决 SQLite 数据库
	 * 在多线程并发访问时出现的 "SQLITE_BUSY" 错误。
	 * 该方法内部委托给 {@link #findByStorageIdNotThreadSafe(Integer)} 完成实际查询。
	 * </p>
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @return  存储源权限配置列表
	 *
	 * @deprecated 使用新版本的权限管理替代
	 */
	@Deprecated
	public synchronized List<PermissionConfig> findByStorageId(Integer storageId) {
		// 通过代理对象调用，确保 AOP 增强（如缓存）生效
		return ((PermissionConfigService) AopContext.currentProxy()).findByStorageIdNotThreadSafe(storageId);
	}


	/**
	 * 根据存储源 ID 查询权限配置（非线程安全版本，带缓存）
	 * <p>
	 * 提示：受 sqlite 限制, 多线程调用此方法会出现 "[SQLITE_BUSY]  The database file is locked (database is locked)" 错误)
	 * 建议使用 {@link PermissionConfigService#findByStorageId(Integer)} 来保证所有数据库访问是线程安全的。
	 * </p>
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @return  存储源权限配置列表（按枚举顺序排序）
	 *
	 * @deprecated 使用新版本的权限管理替代
	 */
	@Transactional(rollbackFor = Exception.class)
	@Deprecated
	@Cacheable(key = "#storageId",
			condition = "#storageId != null")
	public List<PermissionConfig> findByStorageIdNotThreadSafe(Integer storageId) {
		// 数据库查询所有权限配置
		List<PermissionConfig> dbResult = permissionConfigMapper.findByStorageId(storageId);
		// 按照权限枚举的自然顺序排序，使前端展示结果保持一致的顺序
		dbResult.sort(Comparator.comparingInt(permissionConfig -> permissionConfig.getOperator().ordinal()));
		return dbResult;
	}


	/**
	 * 根据存储源 ID 删除权限配置（会清除缓存）
	 *
	 * @param 	storageId
	 * 			存储源 ID
	 * @return  删除的记录数
	 *
	 * @deprecated 使用新版本的权限管理替代
	 */
	@Deprecated
	@CacheEvict(key = "#storageId")
	public int deleteByStorageId(Integer storageId) {
		int deleteSize = permissionConfigMapper.deleteByStorageId(storageId);
		log.info("删除存储源 ID 为 {} 的权限配置 {} 条", storageId, deleteSize);
		return deleteSize;
	}


	/**
	 * 监听存储源删除事件，根据存储源 id 删除相关的权限设置
	 *
	 * @param   storageSourceDeleteEvent
	 *          存储源删除事件
	 */
	@EventListener
	public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
		Integer storageId = storageSourceDeleteEvent.getId();
		// 通过代理对象调用，确保缓存注解生效
		int updateRows = ((PermissionConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
		if (log.isDebugEnabled()) {
			log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源权限设置(老) {} 条",
					storageId,
					storageSourceDeleteEvent.getName(),
					storageSourceDeleteEvent.getType().getDescription(),
					updateRows);
		}
	}


}