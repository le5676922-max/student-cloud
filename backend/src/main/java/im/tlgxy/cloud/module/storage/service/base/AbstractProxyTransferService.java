package im.tlgxy.cloud.module.storage.service.base;

import cn.hutool.core.net.url.UrlBuilder;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.ProxyDownloadUrlUtils;
import im.tlgxy.cloud.core.util.ShareDownloadTokenUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.storage.model.param.ProxyTransferParam;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;

/**
 * 代理传输（上传/下载）抽象基础服务类。
 * <p>
 * 该类继承自 {@link AbstractBaseFileService}，为支持代理上传和代理下载的存储源
 * （如 OneDrive、S3 等）提供统一的代理 URL 生成逻辑。
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>生成代理下载 URL：将文件请求通过 TlgxyCloud 服务器中转，隐藏原始存储源地址</li>
 *   <li>生成代理上传 URL：将文件上传请求通过 TlgxyCloud 服务器中转</li>
 *   <li>签名机制：无论是公开模式还是私有模式，代理链接都携带签名，从密码学层面阻止 URL 篡改攻击</li>
 *   <li>隐藏文件名处理：以 "." 开头的隐藏文件，文件名作为查询参数传递以避免安全拦截</li>
 * </ul>
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承自 {@link ProxyTransferParam}
 * @author zhaojun
 */
public abstract class AbstractProxyTransferService<P extends ProxyTransferParam> extends AbstractBaseFileService<P>{


	/**
	 * 服务器代理下载 URL 前缀。
	 * 完整的代理下载 URL 格式为: {domain}/pd/{storageKey}/{encodedPath}?signature={sign}
	 */
	public static final String PROXY_DOWNLOAD_LINK_PREFIX = "/pd";


	/**
	 * 服务器代理上传 URL 前缀。
	 * 完整的代理上传 URL 格式为: {domain}/file/upload/{storageKey}/{encodedPath}?filename={name}
	 */
	public static final String PROXY_UPLOAD_LINK_PREFIX = "/file/upload";

	/**
	 * 系统配置服务，用于获取域名等系统级配置
	 */
	@Resource
	private SystemConfigService systemConfigService;


	/**
	 * 存储源服务，用于获取存储源的 storageKey
	 */
	@Resource
	private StorageSourceService storageSourceService;


	/**
	 * 获取默认代理下载 URL（不使用存储源参数中的域名）。
	 * <p>
	 * 生成的 URL 使用系统配置中的域名作为基础地址。
	 * </p>
	 *
	 * @param   pathAndName
	 *          文件路径及文件名称（包含用户根目录前缀）
	 *
	 * @return  默认的代理下载 URL
	 */
	public String getProxyDownloadUrl(String pathAndName) {
		return getProxyDownloadUrl(pathAndName, false);
	}


	/**
	 * 获取代理下载 URL，可选择是否使用存储源参数中配置的域名。
	 * <p>
	 * 生成逻辑：
	 * <ol>
	 *   <li>处理以 "." 开头的隐藏文件名，将其作为查询参数附加</li>
	 *   <li>根据存储源是否为私有空间，生成带过期时间的签名或永久签名</li>
	 *   <li>拼接完整的代理下载 URL</li>
	 * </ol>
	 * </p>
	 * <p>
	 * 安全机制：无论是否为私有空间，代理下载链接都必须携带签名，公开模式使用永久签名，
	 * 私有模式使用带过期时间的签名。这样可以从密码学层面阻止通过修改 URL 中的路径访问
	 * 其他用户/路径的文件(#821)。
	 * </p>
	 *
	 * @param   pathAndName
	 *          文件路径及文件名称（包含用户根目录前缀）
	 *
	 * @param 	useParamDomain
	 * 			是否使用存储源参数中的域名替换系统配置中的域名作为下载地址
	 *
	 * @return  代理下载 URL
	 */
	public String getProxyDownloadUrl(String pathAndName, boolean useParamDomain) {
		String path = pathAndName;

		UrlBuilder urlBuilder = UrlBuilder.of();
		String filename = FileUtils.getName(pathAndName);
		// 如果文件名以 "." 开头则为隐藏文件，需要作为查询参数传递，避免被安全过滤器拦截
		if (filename.startsWith(".")) {
			urlBuilder.addQuery("filename", filename);
			path = FileUtils.getParentPath(pathAndName);
		}

		// 无论是否为私有空间, 代理下载链接都必须携带签名,
		// 公开模式使用永久签名, 私有模式使用带过期时间的签名.
		// 这样可以从密码学层面阻止通过修改 URL 中的路径访问其他用户/路径的文件 (#821).
		String signature = ShareAccessContext.isShareAccess()
				? ShareDownloadTokenUtils.generate(storageId, pathAndName, ShareAccessContext.getShareKey(), param.getProxyTokenTime())
				: param.isProxyPrivate()
				? ProxyDownloadUrlUtils.generatorSignature(storageId, pathAndName, param.getProxyTokenTime())
				: ProxyDownloadUrlUtils.generatorPermanentSignature(storageId, pathAndName);
		urlBuilder.addQuery("signature", signature);

		String url;

		// 如果未指定使用参数域名，或参数中未配置域名，则使用系统默认域名
		if (!useParamDomain || StringUtils.isEmpty(param.getDomain())) {
			String domain = systemConfigService.getAxiosFromDomainOrSetting();
			String storageKey = storageSourceService.findStorageKeyById(storageId);
			url = StringUtils.concat(domain, PROXY_DOWNLOAD_LINK_PREFIX, storageKey, StringUtils.encodeAllIgnoreSlashes(path));
		} else {
			// 使用存储源参数中指定的域名
			url = StringUtils.concat(param.getDomain(), StringUtils.encodeAllIgnoreSlashes(path));
		}

		// 如果 URL Builder 中有查询参数，则拼接到 URL 后面
		if (StringUtils.isNotEmpty(urlBuilder.getQueryStr())) {
			url = url + "?" + urlBuilder.getQueryStr();
		}
		return url;
	}


	/**
	 * 获取默认代理上传 URL。
	 * <p>
	 * 生成的 URL 使用系统配置中的域名作为基础地址。
	 * 以 "." 开头的隐藏文件名将作为查询参数传递，避免被安全过滤器拦截。
	 * </p>
	 *
	 * @param   path
	 *          文件路径（文件夹路径，不含文件名）
	 *
	 * @param   name
	 *          文件名称
	 *
	 * @return  代理上传 URL
	 */
	public String getProxyUploadUrl(String path, String name) {
		String domain = systemConfigService.getAxiosFromDomainOrSetting();
		String storageKey = storageSourceService.findStorageKeyById(storageId);

		UrlBuilder urlBuilder = UrlBuilder.of();

		String fullPath = StringUtils.concat(path, name);

		// 以 "." 开头的文件名, 代表是隐藏文件, 需要特殊处理为参数形式，否则会被安全拦截
		if (name.startsWith(".")) {
			urlBuilder.addQuery("filename", name);
			fullPath = path;
		}

		// 拼接完整的代理上传 URL: domain + 上传前缀 + storageKey + 编码后的路径
		String uploadUrl = StringUtils.concat(domain, PROXY_UPLOAD_LINK_PREFIX, storageKey, StringUtils.encodeAllIgnoreSlashes(fullPath));

		// 如果有查询参数（如隐藏文件名），拼接到 URL 后面
		if (StringUtils.isNotEmpty(urlBuilder.getQueryStr())) {
			uploadUrl = uploadUrl + "?" + urlBuilder.getQueryStr();
		}

		return uploadUrl;
	}

	/**
	 * 上传文件到存储源。
	 * <p>
	 * 子类需要根据具体的存储源类型实现文件上传逻辑（如 S3 的 PUT 操作、OneDrive 的上传会话等）。
	 * </p>
	 *
	 * @param   pathAndName
	 *          文件上传路径（包含文件名）
	 *
	 * @param   inputStream
	 *          文件输入流
	 *
	 * @param 	size
	 * 			文件大小（字节）
	 *
	 * @throws  Exception 上传过程中可能出现的异常
	 */
	public abstract void uploadFile(String pathAndName, InputStream inputStream, Long size) throws Exception;


	/**
	 * 代理下载指定文件，将文件内容直接写入 HTTP 响应流。
	 * <p>
	 * 路径契约：pathAndName 必须是相对存储源 basePath 的绝对路径（调用方已拼好用户根目录 /
	 * 分享 sharePath）。实现不得再调用 getCurrentUserBasePath() 二次拼接——代理回源是无登录态
	 * 的新线程，取不到正确的用户根目录。与 {@code getDownloadUrl} 同属"绝对入参"族。
	 * </p>
	 *
	 * @param   pathAndName
	 *          相对 basePath 的绝对路径（已含用户根目录 / sharePath）
	 *
	 * @return  文件响应（通常为 null，因为内容已直接写入响应流）
	 *
	 * @throws  Exception 下载过程中可能出现的异常
	 */
	public abstract ResponseEntity<org.springframework.core.io.Resource> downloadToStream(String pathAndName) throws Exception;

	/**
	 * 获取系统配置服务实例。
	 * <p>
	 * 提供给子类访问系统配置（如域名等）的方法。
	 * </p>
	 *
	 * @return 系统配置服务实例
	 */
    protected SystemConfigService getSystemConfigService() {
        return systemConfigService;
    }

}
