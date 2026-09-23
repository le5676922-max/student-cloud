package im.tlgxy.cloud.module.storage.service.base;

/**
 * 需要刷新 Token 的存储源服务接口。
 * <p>
 * 该接口继承自 {@link BaseFileService}，为需要定时刷新认证令牌（如 OAuth2 AccessToken）
 * 的存储源提供一个统一的刷新入口。实现此接口的存储源通常使用基于 OAuth2 的认证方式，
 * 其 AccessToken 有过期时间，需要定期通过 RefreshToken 来获取新的 AccessToken。
 * </p>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>OneDrive（国际版 / 世纪互联版）—— 使用 Microsoft OAuth2 认证</li>
 *   <li>SharePoint —— 使用 Microsoft OAuth2 认证</li>
 *   <li>其他需要通过 OAuth2 RefreshToken 周期刷新 AccessToken 的存储源</li>
 * </ul>
 * </p>
 * <p>
 * 刷新机制通常由定时任务驱动，定时调用 {@link #refreshAccessToken()} 方法，
 * 确保存储源的 AccessToken 始终有效，避免因 Token 过期导致 API 调用失败。
 * </p>
 * <p>
 * 典型的刷新流程：
 * <ol>
 *   <li>检查当前 AccessToken 是否过期</li>
 *   <li>如果过期，使用 RefreshToken 向认证服务器请求新的 AccessToken</li>
 *   <li>将新的 AccessToken 和 RefreshToken 保存到数据库和缓存中</li>
 *   <li>如果刷新失败，记录失败状态，等待下次重试</li>
 * </ol>
 * </p>
 *
 * @author zhaojun
 */
public interface RefreshTokenService extends BaseFileService {

	/**
	 * 刷新存储源的 AccessToken 或其他需要定时刷新的 Token。
	 * <p>
	 * 此方法的实现应包含完整的 Token 刷新逻辑：
	 * <ol>
	 *   <li>从数据库获取当前的 RefreshToken</li>
	 *   <li>向 OAuth2 认证服务器发送刷新请求</li>
	 *   <li>解析响应，获取新的 AccessToken 和 RefreshToken</li>
	 *   <li>将新的 Token 信息更新到数据库和缓存</li>
	 * </ol>
	 * </p>
	 * <p>
	 * 注意事项：
	 * <ul>
	 *   <li>此方法可能被多个线程并发调用，实现时需要保证线程安全</li>
	 *   <li>刷新失败时应更新缓存中的状态为失败，避免重复尝试</li>
	 *   <li>建议使用双重检查锁定（DCL）减少不必要的刷新操作</li>
	 * </ul>
	 * </p>
	 *
	 * @throws Exception	刷新 Token 过程中出现的异常（网络异常、认证失败等）
	 */
	void refreshAccessToken() throws Exception;

}