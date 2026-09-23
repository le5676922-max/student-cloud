package im.tlgxy.cloud.module.storage.model.bo;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import im.tlgxy.cloud.module.storage.model.dto.RefreshTokenInfoDTO;
import lombok.Data;
import lombok.ToString;

import java.util.Date;

/**
 * 刷新令牌缓存业务对象（BO）
 * <p>
 * 用于缓存各存储源的 OAuth2 令牌刷新信息。基于 Hutool 的 FIFO（先进先出）缓存实现，
 * 最多缓存 1024 条记录。当存储源（如 OneDrive、Google Drive 等）的访问令牌过期时，
 * 通过刷新令牌（Refresh Token）获取新的访问令牌，刷新结果被缓存在此对象中。
 * </p>
 * <p>
 * 使用场景：多个存储源可能同时需要刷新令牌，缓存可以避免短时间内重复刷新，
 * 提高系统响应速度。缓存的刷新信息通过 isExpired() 方法判断是否需要重新刷新。
 * </p>
 *
 * @author zhaojun
 */
@ToString
public class RefreshTokenCacheBO {

	/**
	 * 基于 FIFO 策略的令牌刷新信息缓存
	 * Key 为存储源 ID，Value 为刷新信息的内部类实例。最多存储 1024 条记录。
	 */
	private static final Cache<Integer, RefreshTokenInfo> REFRESH_TOKEN_INFO_CACHE = CacheUtil.newFIFOCache(1024);

	/**
	 * 将存储源的令牌刷新信息存入缓存
	 *
	 * @param storageId         存储源 ID
	 * @param refreshTokenInfo  刷新信息对象
	 */
	public static void putRefreshTokenInfo(Integer storageId, RefreshTokenInfo refreshTokenInfo) {
		refreshTokenInfo.setStorageId(storageId);
		REFRESH_TOKEN_INFO_CACHE.put(storageId, refreshTokenInfo);
	}

	/**
	 * 从缓存中获取存储源的令牌刷新信息
	 *
	 * @param storageId  存储源 ID
	 * @return 刷新信息对象，如果缓存中不存在则返回 null
	 */
	public static RefreshTokenInfo getRefreshTokenInfo(Integer storageId) {
		return REFRESH_TOKEN_INFO_CACHE.get(storageId);
	}

	/**
	 * 令牌刷新信息实体内部类
	 * <p>
	 * 封装了单次令牌刷新的完整结果，包括是否成功、刷新时间、返回数据和过期判断逻辑。
	 * </p>
	 */
	@Data
	public static class RefreshTokenInfo {

		/** 关联的存储源 ID */
		private Integer storageId;

		/** 刷新操作是否成功 */
		private boolean success;

		/** 最后一次刷新操作的时间 */
		private Date lastRefreshTime;

		/** 刷新失败时的错误消息 */
		private String msg;

		/** 刷新成功时返回的令牌数据（包含 access_token、过期时间等） */
		private RefreshTokenInfoDTO data;

		/**
		 * 创建刷新成功的响应对象
		 *
		 * @param data  刷新返回的令牌数据
		 * @return 标记为成功的刷新信息对象
		 */
		public static RefreshTokenInfo success(RefreshTokenInfoDTO data) {
			RefreshTokenInfo info = new RefreshTokenInfo();
			info.setSuccess(true);
			info.setLastRefreshTime(new Date());
			info.setData(data);
			return info;
		}

		/**
		 * 创建刷新失败的响应对象
		 *
		 * @param msg  失败原因描述
		 * @return 标记为失败的刷新信息对象
		 */
		public static RefreshTokenInfo fail(String msg) {
			RefreshTokenInfo info = new RefreshTokenInfo();
			info.setSuccess(false);
			info.setMsg(msg);
			info.setLastRefreshTime(new Date());
			return info;
		}

		/**
		 * 判断当前缓存的令牌是否已过期
		 * <p>
		 * 以下任一情况均视为已过期：
		 * <ul>
		 *   <li>刷新操作未成功</li>
		 *   <li>从未进行过刷新（lastRefreshTime 为 null）</li>
		 *   <li>没有返回数据或返回数据中没有过期时间</li>
		 *   <li>距令牌过期时间不足 5 分钟（提前刷新，避免临界状态）</li>
		 * </ul>
		 * </p>
		 *
		 * @return true 表示已过期需要刷新，false 表示令牌仍然有效
		 */
		public boolean isExpired() {
			// 如果之前刷新就失败了，视为已过期
			if (!success) {
				return true;
			}

			// 从未刷新过，视为已过期
			if (lastRefreshTime == null) {
				return true;
			}

			// 没有令牌数据或没有过期时间，视为已过期
			if (data == null || data.getExpiredAt() == null) {
				return true;
			}

			// 计算距离令牌过期还有多长时间
			// expiredAt 是秒级时间戳，需要转换为毫秒
			long expireTime = data.getExpiredAt() * 1000L;
			long currentTime = System.currentTimeMillis();
			long timeDiff = expireTime - currentTime;
			// 如果距离过期不足 5 分钟，提前标记为过期，留出缓冲时间避免请求失败
			return timeDiff < 5 * 60 * 1000L;
		}

	}

}