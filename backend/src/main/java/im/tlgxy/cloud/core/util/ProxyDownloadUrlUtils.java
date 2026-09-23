package im.tlgxy.cloud.core.util;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.symmetric.SymmetricAlgorithm;
import cn.hutool.crypto.symmetric.SymmetricCrypto;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理下载链接工具类
 * <p>
 * 负责生成和校验代理下载链接的签名，防止下载链接被恶意篡改或绕过。
 * 使用 AES 对称加密算法对签名内容进行加密，签名内容包含存储源 ID、文件路径及过期时间。
 * 支持两种签名模式：
 * <ul>
 *   <li><b>普通签名</b>：带过期时间，适用于私有空间，链接在一定时间后失效。</li>
 *   <li><b>永久签名</b>：不带过期时间，适用于公开空间，但仍携带路径信息用于路径隔离校验。</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class ProxyDownloadUrlUtils {

	/**
	 * 系统配置服务，用于获取 AES 加密密钥等系统配置项。
	 * 延迟加载，首次使用时从 Spring 容器中获取。
	 */
	private static SystemConfigService systemConfigService;

	/**
	 * 签名内容各部分之间的分隔符。
	 * 签名内容格式：存储源ID + 分隔符 + 文件路径 + 分隔符 + 过期时间戳(或永久标记)
	 */
	private static final String PROXY_DOWNLOAD_LINK_DELIMITER = ":";

	/**
	 * 永久签名的标记 (写入签名内容第三段以代替过期时间戳)。
	 * 当签名第三段为该值时，表示该签名为永久有效，跳过过期时间校验。
	 */
	private static final String PERMANENT_SIGNATURE_MARKER = "PERMANENT";

	/**
	 * AES 加密器缓存，以 AES 密钥的十六进制字符串为 key 缓存 SymmetricCrypto 实例，
	 * 避免每次加解密时重复创建加密器对象，提高性能。
	 */
	private static final Map<String, SymmetricCrypto> AES_CACHE = new HashMap<>();

	/**
	 * 服务器代理下载 URL 有效期 (秒)，默认值为 1800 秒（30 分钟）。
	 */
	public static final Integer PROXY_DOWNLOAD_LINK_EFFECTIVE_SECOND = 1800;

	/**
	 * 生成普通签名
	 * <p>
	 * 根据存储源 ID、文件路径和有效时间，生成一段经过 AES 加密的签名串。
	 * 签名内部结构为：<code>storageId:pathAndName:expiredTimestamp</code>，
	 * 其中过期时间戳为当前时间加上有效秒数后的毫秒值。
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID，用于标识文件所属的存储源
	 *
	 * @param 	pathAndName
	 * 			文件路径及文件名称，用于定位具体文件
	 *
	 * @param 	effectiveSecond
	 * 			有效时间，单位：秒。如果为 null 或小于 1，则使用默认的 30 分钟
	 *
	 * @return	AES 加密后的十六进制签名字符串
	 */
	public static String generatorSignature(Integer storageId, String pathAndName, Integer effectiveSecond) {
		// 如果有效时间为空或无效, 则使用默认的 30 分钟过期时间
		if (effectiveSecond == null || effectiveSecond < 1) {
			effectiveSecond = PROXY_DOWNLOAD_LINK_EFFECTIVE_SECOND;
		}

		// 计算过期时间：当前时间偏移指定秒数后得到过期时间点的毫秒时间戳
		long second = DateUtil.offsetSecond(DateUtil.date(), effectiveSecond).getTime();
		// 拼接签名原始内容：存储源ID + 分隔符 + 文件路径 + 分隔符 + 过期时间戳
		String content = storageId + PROXY_DOWNLOAD_LINK_DELIMITER + pathAndName + PROXY_DOWNLOAD_LINK_DELIMITER + second;

		// 使用 AES 加密签名内容并返回十六进制密文
		return encrypt(content);
	}


	/**
	 * 生成永久签名
	 * <p>
	 * 用于代理公开下载场景，签名内容包含路径但不带过期时间。
	 * 签名内部结构为：<code>storageId:pathAndName:PERMANENT</code>。
	 * 这样即使关闭了"私有空间"开关，下载链接仍然携带签名，
	 * 服务端通过校验签名内容中的路径来阻止任意路径访问（路径隔离），
	 * 防止恶意用户通过修改链接参数访问其他文件。
	 * </p>
	 *
	 * @param 	storageId
	 * 			存储源 ID，用于标识文件所属的存储源
	 *
	 * @param 	pathAndName
	 * 			文件路径及文件名称，用于定位具体文件
	 *
	 * @return	AES 加密后的十六进制永久签名字符串
	 */
	public static String generatorPermanentSignature(Integer storageId, String pathAndName) {
		// 拼接签名原始内容：存储源ID + 分隔符 + 文件路径 + 分隔符 + 永久标记
		String content = storageId + PROXY_DOWNLOAD_LINK_DELIMITER + pathAndName + PROXY_DOWNLOAD_LINK_DELIMITER + PERMANENT_SIGNATURE_MARKER;
		// 使用 AES 加密签名内容并返回十六进制密文
		return encrypt(content);
	}


	/**
	 * 校验签名是否有效
	 * <p>
	 * 对签名进行解密，然后依次校验：
	 * <ol>
	 *   <li>签名格式是否正确（解密后是否能按分隔符拆分为三段）</li>
	 *   <li><b>路径匹配校验</b>：存储源 ID 和文件路径必须与期望值完全一致，这是签名最核心的隔离能力。</li>
	 *   <li>如果是永久签名（第三段为 PERMANENT 标记），则跳过过期校验，直接返回 true。</li>
	 *   <li>如果是普通签名，则校验当前时间是否在过期时间之前。</li>
	 * </ol>
	 * </p>
	 *
	 * @param 	expectedStorageId
	 * 			期望的存储源 ID，用于与签名中携带的存储源 ID 进行比对
	 *
	 * @param 	expectedPathAndName
	 * 			期望的文件路径及名称，用于与签名中携带的路径进行比对
	 *
	 * @param 	signature
	 * 			待校验的签名字符串（AES 加密后的十六进制密文）
	 *
	 * @return	签名有效返回 true，否则返回 false（签名格式错误、路径不匹配、已过期、解密异常等情况均返回 false）
	 */
	public static boolean validSignatureExpired(Integer expectedStorageId, String expectedPathAndName, String signature) {
		// 签名为空，直接返回无效
		if (StringUtils.isEmpty(signature)) {
			return false;
		}
		// 延迟加载 SystemConfigService，避免循环依赖
		if (systemConfigService == null) {
			systemConfigService = SpringUtil.getBean(SystemConfigService.class);
		}

		// 获取系统配置的 AES 密钥，并从缓存中获取或创建对应的加密器
		String aesHexKey = systemConfigService.getAesHexKeyOrGenerate();
		SymmetricCrypto aes = AES_CACHE.computeIfAbsent(aesHexKey, k -> new SymmetricCrypto(SymmetricAlgorithm.AES, HexUtil.decodeHex(k)));

		// 记录当前时间，用于后续过期校验
		long currentTimeMillis = System.currentTimeMillis();

		String storageId = null;
		String pathAndName = null;
		String expiredSecond = null;

		try {
			// 使用 AES 解密签名，得到原始内容
			String decryptStr = aes.decryptStr(signature);
			// 按分隔符拆分解密后的内容，期望得到三段：存储源ID、文件路径、过期时间戳(或永久标记)
			List<String> split = StringUtils.split(decryptStr, PROXY_DOWNLOAD_LINK_DELIMITER);
			storageId = split.get(0);
			pathAndName = split.get(1);
			expiredSecond = split.get(2);

			// 核心校验：存储源 ID 和文件路径必须完全匹配，防止跨存储源或跨路径的越权访问
			boolean storageAndPathMatch = StringUtils.equals(storageId, Convert.toStr(expectedStorageId))
					&& StringUtils.equals(StringUtils.concat(pathAndName), StringUtils.concat(expectedPathAndName));

			if (!storageAndPathMatch) {
				log.warn("校验链接不匹配, signature: {}, storageId={}, pathAndName={}, expiredSecond={}, now:={}", signature, storageId, pathAndName, expiredSecond, currentTimeMillis);
				return false;
			}

			// 如果第三段是永久标记，说明该签名是永久有效的，跳过过期时间校验，直接返回 true
			if (PERMANENT_SIGNATURE_MARKER.equals(expiredSecond)) {
				return true;
			}

			// 普通签名：校验当前时间是否在过期时间之前（未过期则有效）
			if (currentTimeMillis < Convert.toLong(expiredSecond)) {
				return true;
			}

			// 签名已过期，记录警告日志
			log.warn("校验链接已过期, signature: {}, storageId={}, pathAndName={}, expiredSecond={}, now:={}", signature, storageId, pathAndName, expiredSecond, currentTimeMillis);
		} catch (Exception e) {
			// 解密或解析过程中发生异常（如签名被篡改导致解密失败、格式不正确等），记录错误日志并返回无效
			log.error("校验签名链接异常, signature: {}, storageId={}, pathAndName={}, expiredSecond={}, now:={}", signature, storageId, pathAndName, expiredSecond, currentTimeMillis);
			return false;
		}

		return false;
	}


	/**
	 * 使用 AES 对称加密算法对内容进行加密
	 * <p>
	 * 加密密钥从系统配置中获取（通过 {@link SystemConfigService#getAesHexKeyOrGenerate()}），
	 * 如果密钥不存在则自动生成一个。加密器实例会被缓存在 {@link #AES_CACHE} 中，
	 * 以密钥为 key，避免重复创建加密器对象。
	 * </p>
	 *
	 * @param 	content
	 * 			待加密的原始内容字符串
	 *
	 * @return	AES 加密后的十六进制密文字符串
	 */
	private static String encrypt(String content) {
		// 延迟加载 SystemConfigService，避免循环依赖
		if (systemConfigService == null) {
			systemConfigService = SpringUtil.getBean(SystemConfigService.class);
		}
		// 获取系统配置的 AES 密钥（十六进制字符串形式）
		String aesHexKey = systemConfigService.getAesHexKeyOrGenerate();
		// 从缓存中获取或创建 AES 加密器：将十六进制密钥解码为字节数组后初始化 SymmetricCrypto
		SymmetricCrypto aes = AES_CACHE.computeIfAbsent(aesHexKey, k -> new SymmetricCrypto(SymmetricAlgorithm.AES, HexUtil.decodeHex(k)));
		// 加密内容并返回十六进制密文
		return aes.encryptHex(content);
	}

}