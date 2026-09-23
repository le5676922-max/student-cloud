package im.tlgxy.cloud.module.readme.service;

import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.HttpUtil;
import im.tlgxy.cloud.core.util.PatternMatcherUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.readme.mapper.ReadmeConfigMapper;
import im.tlgxy.cloud.module.readme.model.entity.ReadmeConfig;
import im.tlgxy.cloud.module.readme.model.enums.ReadmeDisplayModeEnum;
import im.tlgxy.cloud.module.readme.model.enums.ReadmePathModeEnum;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.event.StorageSourceCopyEvent;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存储源 README 配置 Service
 * <p>
 * 封装 README 文档配置的增删改查和路径匹配业务逻辑，包括：
 * <ul>
 *   <li>按存储源 ID 查询 README 配置（带缓存）</li>
 *   <li>批量保存 README 配置（事务性操作，先删后增）</li>
 *   <li>按路径查找匹配的 README 配置</li>
 *   <li>兼容模式：自动读取目录下的 readme.md 文件内容</li>
 *   <li>监听存储源删除事件，自动清理关联的 README 配置</li>
 *   <li>监听存储源复制事件，自动复制 README 配置到新存储源</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Service
@CacheConfig(cacheNames = "readmeConfig")
public class ReadmeConfigService {

	/** README 配置 Mapper */
	@Resource
	private ReadmeConfigMapper readmeConfigMapper;

	/**
	 * 根据存储源 ID 查询文档配置（带缓存）
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @return  存储源文档配置列表
	 */
	@Cacheable(key = "#storageId",
				condition = "#storageId != null")
	public List<ReadmeConfig> findByStorageId(Integer storageId){
		return readmeConfigMapper.findByStorageId(storageId);
	}


	/**
	 * 批量保存存储源 README 配置
	 * <p>事务性操作：先删除该存储源的所有旧配置，再逐条插入新配置。</p>
	 *
	 * @param   storageId
	 *          存储源 ID
	 *
	 * @param   readmeConfigList
	 *          存储源 README 配置列表
	 */
	@Transactional(rollbackFor = Exception.class)
	public void batchSave(Integer storageId, List<ReadmeConfig> readmeConfigList) {
		// 先删除旧配置（通过代理调用确保缓存注解生效）
		((ReadmeConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);

		log.info("更新存储源 ID 为 {} 的目录文档配置 {} 条", storageId, readmeConfigList.size());

		// 逐条插入新配置
		readmeConfigList.forEach(readmeConfig -> {
			// 清空 ID 让数据库自动生成
			readmeConfig.setId(null);
			readmeConfig.setStorageId(storageId);
			readmeConfigMapper.insert(readmeConfig);

			if (log.isDebugEnabled()) {
				log.debug("新增目录文档, 存储源 ID: {}, 表达式: {}, 描述: {}, 显示模式: {}",
						readmeConfig.getStorageId(), readmeConfig.getExpression(),
						readmeConfig.getDescription(), readmeConfig.getDisplayMode().getValue());
			}
		});
	}


	/**
	 * 根据存储源 ID 删除所有 README 配置（会清除缓存）
	 *
	 * @param 	storageId
	 * 			存储源 ID
	 * @return  删除的记录数
	 */
	@CacheEvict(key = "#storageId")
	public int deleteByStorageId(Integer storageId) {
		int deleteSize = readmeConfigMapper.deleteByStorageId(storageId);
		log.info("删除存储源 ID 为 {} 的目录文档配置 {} 条", storageId, deleteSize);
		return deleteSize;
	}

	/**
	 * 监听存储源删除事件，根据存储源 id 删除相关的目录文档设置
	 *
	 * @param   storageSourceDeleteEvent
	 *          存储源删除事件
	 */
	@EventListener
	public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
		Integer storageId = storageSourceDeleteEvent.getId();
		// 通过代理对象调用，确保缓存注解生效
		int updateRows = ((ReadmeConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
		if (log.isDebugEnabled()) {
			log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源目录文档设置 {} 条",
					storageId,
					storageSourceDeleteEvent.getName(),
					storageSourceDeleteEvent.getType().getDescription(),
					updateRows);
		}
	}

	/**
	 * 根据存储源指定路径查找匹配的 README 配置
	 * <p>遍历该存储源的所有 README 规则，使用 glob 模式匹配路径，返回第一个匹配的配置。</p>
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @param   path
	 *          文件夹路径
	 *
	 * @return  匹配的 README 配置，未找到则返回 null
	 */
	public ReadmeConfig findReadmeByPath(Integer storageId, String path) {
		// 获取该存储源的所有 README 配置
		List<ReadmeConfig> readmeConfigList = ((ReadmeConfigService) AopContext.currentProxy()).findByStorageId(storageId);
		return getReadmeByTestPattern(storageId, readmeConfigList, path);
	}


	/**
	 * 根据存储源和路径获取 README 配置（支持兼容模式）
	 * <p>
	 * 当 compatibilityReadme 为 true 时，尝试读取指定目录下的 readme.md 文件内容。
	 * 当 compatibilityReadme 为 false 时，使用数据库中配置的 README 规则进行匹配。
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID
	 *
	 * @param 	path
	 * 			存储源路径
	 *
	 * @param 	compatibilityReadme
	 * 			是否启用兼容模式（读取目录下的 readme.md 文件）
	 *
	 * @return  目录对应的 README 配置
	 */
	public ReadmeConfig getByStorageAndPath(Integer storageId, String path, Boolean compatibilityReadme) {
		// 创建默认配置（显示模式为底部）
		ReadmeConfig readmeByPath = new ReadmeConfig();
		readmeByPath.setStorageId(storageId);
		readmeByPath.setDisplayMode(ReadmeDisplayModeEnum.BOTTOM);
		if (BooleanUtils.isTrue(compatibilityReadme)) {
			// 兼容模式：尝试读取目录下的 readme.md 文件
			try {
				AbstractBaseFileService<IStorageParam> abstractBaseFileService = StorageSourceContext.getByStorageId(storageId);
				String pathAndName = StringUtils.concat(path, "readme.md");
				// 获取 readme.md 文件信息
				FileItemResult fileItem = abstractBaseFileService.getFileItem(pathAndName);
				if (fileItem != null) {
					String url = fileItem.getUrl();
					// 通过 HTTP 请求获取文件文本内容
					String readmeText = HttpUtil.getTextContent(url);
					if (log.isDebugEnabled()) {
						log.debug("存储源 {} 兼容获取目录 {} 下的 readme.md 文件成功, url: {}", storageId, path, url);
					}
					readmeByPath.setReadmeText(readmeText);
				}
			} catch (Exception e) {
				log.error("存储源 {} 兼容获取目录 {} 下的 readme.md 文件失败", storageId, path, e);
			}
		} else {
			// 正常模式：通过数据库中配置的规则匹配
			ReadmeConfig dbReadmeConfig = ((ReadmeConfigService) AopContext.currentProxy()).findReadmeByPath(storageId, path);
			if (dbReadmeConfig != null) {
				readmeByPath = dbReadmeConfig;
			}
		}

		return readmeByPath;
	}


	/**
	 * 根据规则表达式和测试路径进行匹配
	 * <p>遍历所有 README 规则，使用 glob 模式匹配路径。支持绝对路径和相对路径两种匹配模式。</p>
	 *
	 * @param   storageId
	 *          存储源 ID（用于日志记录）
	 *
	 * @param   patternList
	 *          README 规则列表
	 *
	 * @param   test
	 *          要测试的路径字符串
	 *
	 * @return  第一个匹配的 README 配置，没有匹配则返回 null
	 */
	private ReadmeConfig getReadmeByTestPattern(Integer storageId, List<ReadmeConfig> patternList, String test) {
		// 如果规则列表为空, 则可直接返回空.
		if (CollectionUtils.isEmpty(patternList)) {
			if (log.isDebugEnabled()) {
				log.debug("目录文档规则列表为空, 存储源 ID: {}, 测试字符串: {}", storageId, test);
			}
			return null;
		}

		// 遍历规则进行路径匹配
		for (ReadmeConfig readmeConfig : patternList) {
			String expression = readmeConfig.getExpression();

			// 表达式为空则跳过
			if (StringUtils.isEmpty(expression)) {
				if (log.isDebugEnabled()) {
					log.debug("存储源 {} 目录文档规则表达式为空: {}, 测试字符串: {}, 表达式为空，跳过该规则比对", storageId, expression, test);
				}
				continue;
			}

			try {
                ReadmePathModeEnum pathMode = readmeConfig.getPathMode();
                boolean match;

                // 根据路径模式选择不同的匹配方式
                if (pathMode == ReadmePathModeEnum.ABSOLUTE) {
                    // 绝对路径模式：匹配时拼接用户根目录
                    AbstractBaseFileService<IStorageParam> abstractBaseFileService = StorageSourceContext.getByStorageId(storageId);
                    String currentUserBasePath = abstractBaseFileService.getCurrentUserBasePath();
                    match = PatternMatcherUtils.testCompatibilityGlobPattern(expression, StringUtils.concat(currentUserBasePath, test));
                } else {
                    // 相对路径模式：直接匹配
                    match = PatternMatcherUtils.testCompatibilityGlobPattern(expression, test);
                }

				if (log.isDebugEnabled()) {
					log.debug("存储源 {} 目录文档规则表达式: {}, 测试字符串: {}, 匹配结果: {}", storageId, expression, test, match);
				}

				// 返回第一个匹配的配置
				if (match) {
					return readmeConfig;
				}
			} catch (Exception e) {
				log.error("存储源 {} 目录文档规则表达式: {}, 测试字符串: {}, 匹配异常，跳过该规则.", storageId, expression, test, e);
			}
		}

		return null;
	}


	/**
	 * 监听存储源复制事件, 复制存储源时, 复制存储源目录文档设置
	 * <p>将源存储源的所有 README 配置复制到新创建的存储源中。</p>
	 *
	 * @param   storageSourceCopyEvent
	 *          存储源复制事件
	 */
	@EventListener
	public void onStorageSourceCopy(StorageSourceCopyEvent storageSourceCopyEvent) {
		Integer fromId = storageSourceCopyEvent.getFromId();
		Integer newId = storageSourceCopyEvent.getNewId();

		// 获取源存储源的所有 README 配置
		List<ReadmeConfig> readmeConfigList = ((ReadmeConfigService) AopContext.currentProxy()).findByStorageId(fromId);

		// 逐条复制到新存储源
		readmeConfigList.forEach(readmeConfig -> {
			ReadmeConfig newReadmeConfig = new ReadmeConfig();
			BeanUtils.copyProperties(readmeConfig, newReadmeConfig);
			// 清空 ID 让数据库自动生成，设置新的存储源 ID
			newReadmeConfig.setId(null);
			newReadmeConfig.setStorageId(newId);
			readmeConfigMapper.insert(newReadmeConfig);
		});

		log.info("复制存储源 ID 为 {} 的存储源目录文档设置到存储源 ID 为 {} 成功, 共 {} 条", fromId, newId, readmeConfigList.size());
	}

}