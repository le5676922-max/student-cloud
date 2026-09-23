package im.tlgxy.cloud.module.log.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import im.tlgxy.cloud.module.link.event.DeleteExpireLinkEvent;
import im.tlgxy.cloud.module.log.mapper.DownloadLogMapper;
import im.tlgxy.cloud.module.log.model.entity.DownloadLog;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 下载日志 Service
 * <p>
 * 封装下载日志的增删改查业务逻辑，包括：
 * <ul>
 *   <li>保存下载日志</li>
 *   <li>分页查询下载日志</li>
 *   <li>单条和批量删除</li>
 *   <li>按查询条件删除</li>
 *   <li>监听存储源删除事件，自动清理关联的下载日志</li>
 *   <li>监听过期短链删除事件，自动清理关联的下载日志</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Service
public class DownloadLogService {

	/** 下载日志 Mapper */
	@Resource
	private DownloadLogMapper downloadLogMapper;

	/**
	 * 保存一条下载日志记录
	 *
	 * @param downloadLog 要保存的下载日志实体
	 */
	public void save(DownloadLog downloadLog) {
		downloadLogMapper.insert(downloadLog);
	}

	/**
	 * 分页查询下载日志
	 *
	 * @param pages        分页参数对象
	 * @param queryWrapper 查询条件包装器
	 * @return 分页查询结果
	 */
	public Page<DownloadLog> selectPage(Page<DownloadLog> pages, Wrapper<DownloadLog> queryWrapper) {
		return downloadLogMapper.selectPage(pages, queryWrapper);
	}

	/**
	 * 根据 ID 删除单条下载日志
	 *
	 * @param id 日志 ID
	 */
	public void removeById(Integer id) {
		downloadLogMapper.deleteById(id);
	}

	/**
	 * 批量删除下载日志（事务性操作）
	 *
	 * @param ids 要删除的日志 ID 列表
	 */
	@Transactional(rollbackFor = Exception.class)
	public void removeBatchByIds(List<Integer> ids) {
		downloadLogMapper.deleteBatchIds(ids);
	}

	/**
	 * 根据查询条件删除下载日志
	 *
	 * @param queryWrapper 查询条件包装器
	 */
	public void deleteByQueryWrapper(Wrapper<DownloadLog> queryWrapper) {
		downloadLogMapper.delete(queryWrapper);
	}

	/**
	 * 根据存储源 key 删除所有关联的下载日志
	 *
	 * @param storageKey 存储源 key
	 * @return 删除的记录数
	 */
	public int deleteByStorageKey(String storageKey) {
		int deleteSize = downloadLogMapper.deleteByStorageKey(storageKey);
		log.info("删除存储源 ID 为 {} 的直/短链下载日志 {} 条", storageKey, deleteSize);
		return deleteSize;
	}

	/**
	 * 监听存储源删除事件，根据存储源 id 删除相关的下载日志
	 * <p>
	 * 使用 AopContext.currentProxy() 获取当前代理对象，确保事务和缓存注解生效。
	 * </p>
	 *
	 * @param   storageSourceDeleteEvent
	 *          存储源删除事件
	 */
	@EventListener
	public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
		String storageKey = storageSourceDeleteEvent.getKey();
		// 通过代理对象调用，确保 @Transactional 等 AOP 增强生效
		int updateRows = ((DownloadLogService) AopContext.currentProxy()).deleteByStorageKey(storageKey);
		if (log.isDebugEnabled()) {
			log.debug("删除存储源 [id {}, key: {}, name: {}, type: {}] 时，关联删除存储源直/短链下载日志 {} 条",
					storageSourceDeleteEvent.getId(),
					storageKey,
					storageSourceDeleteEvent.getName(),
					storageSourceDeleteEvent.getType().getDescription(),
					updateRows);
		}
	}

	/**
	 * 删除所有过期的短链下载日志
	 *
	 * @return  删除的条数
	 */
	public int deleteExpireShortLinkLog() {
		return downloadLogMapper.deleteExpireShortLinkLog();
	}

	/**
	 * 监听删除过期短链事件，自动清理关联的下载日志
	 *
	 * @param event 删除过期短链事件
	 */
	@EventListener(classes = DeleteExpireLinkEvent.class)
	public void deleteExpireShortLinkLog(DeleteExpireLinkEvent event) {
		int updateRows = deleteExpireShortLinkLog();
		log.info("删除过期短链关联删除日志 {} 条", updateRows);
	}

}