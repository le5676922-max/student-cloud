package im.tlgxy.cloud.core.controller;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 前端首页控制器
 * <p>
 * 负责处理前端的入口请求，主要解决以下两个问题：
 * <ol>
 *   <li>Vue History 模式下，前端路由通过 URL 路径进行导航，当用户直接访问或刷新非根路径的页面时，
 *       后端没有对应的路由映射，会返回 404。本控制器将所有未匹配的请求重定向到 index.html，
 *       由前端路由接管处理。</li>
 *   <li>在返回 index.html 时，根据系统配置动态替换页面标题（title）和网站图标（favicon），
 *       实现个性化的站点外观。</li>
 * </ol>
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Controller
public class FrontIndexController {

	/**
	 * 系统配置服务，用于获取站点名称、favicon 地址等系统设置
	 */
	@Resource
	private SystemConfigService systemConfigService;
	
	@Resource
	private WebProperties webProperties;

	@RequestMapping(value = { "/"})
	@ResponseBody
	public ResponseEntity<String> redirect() {
		// 读取 resources/static/index.html 文件修改 title 和 favicon 后返回
		ResourceLoader resourceLoader = new FileSystemResourceLoader();
		String[] staticLocations = webProperties.getResources().getStaticLocations();

		// 如果 staticLocations 里没有包含 file:static/, 则手动添加，确保能扫描到文件系统中的静态资源
		boolean fileStaticExist = false;
		for (String staticLocation : staticLocations) {
			if (staticLocation.startsWith("file:")) {
				fileStaticExist = true;
				break;
			}
		}
		if (!fileStaticExist) {
			staticLocations = org.apache.commons.lang3.ArrayUtils.add(staticLocations, "file:static/");
		}

		// 遍历所有静态资源路径，查找并读取 index.html 文件
		for (String staticLocation : staticLocations) {
			org.springframework.core.io.Resource resource = resourceLoader.getResource(staticLocation + "/index.html");
			boolean exists = resource.exists();
			if (exists) {
				String content;
				try {
					// 以 UTF-8 编码读取 index.html 文件内容
					content = resource.getContentAsString(StandardCharsets.UTF_8);
					if (log.isTraceEnabled()) {
						log.trace("读取 index.html 文件成功, 文件路径: {}", staticLocation);
					}
				} catch (Exception e) {
					// 资源存在但读取失败（如权限问题），记录错误日志并返回 500
					log.error("{} 资源存在但读取 index.html 文件失败.", staticLocation);
					return ResponseEntity.status(500).body("static index.html read error");
				}

				// 获取系统配置信息
				SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();

				// 替换为系统设置中的站点名称，将默认的 <title>TlgxyCloud</title> 替换为自定义名称
				String siteName = systemConfig.getSiteName();
				if (StringUtils.isNotBlank(siteName)) {
					content = content.replace("<title>TlgxyCloud</title>", "<title>" + siteName + "</title>");
				}

				// 替换为系统设置中的 favicon 地址，将默认图标替换为自定义图标
				String faviconUrl = systemConfig.getFaviconUrl();
				if (StringUtils.isNotBlank(faviconUrl)) {
					content = content.replace("/favicon.svg", faviconUrl);
				}

				// 添加缓存控制头：max-age=600 表示浏览器缓存 10 分钟
				return ResponseEntity.ok()
						.header("Cache-Control", "max-age=600, must-revalidate, proxy-revalidate")
						.header("Pragma", "no-cache")
						.body(content);
			}
		}

		// 所有静态资源路径都未找到 index.html 文件，返回 404
		return ResponseEntity.status(404).body("static index.html not found");
	}

	/**
	 * 获取访客模式的首页内容
	 * <p>
	 * 当系统配置了访客模式时，返回访客专属的首页 HTML 内容。
	 * 访客首页与普通首页可能展示不同的界面元素和功能入口。
	 * </p>
	 *
	 * @return 访客模式的首页 HTML 内容字符串
	 */
	@RequestMapping(value = { "/guest"})
	@ResponseBody
	public String guest() {
		SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
		return systemConfig.getGuestIndexHtml();
	}

}
