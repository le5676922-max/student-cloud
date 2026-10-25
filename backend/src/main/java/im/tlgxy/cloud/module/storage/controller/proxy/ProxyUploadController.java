package im.tlgxy.cloud.module.storage.controller.proxy;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.SpringMvcUtils;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.beans.Beans;

/**
 * 服务端代理上传 Controller
 *
 * @author zhaojun
 */
@Tag(name = "服务端代理上传")
@RestController
public class ProxyUploadController {
    @jakarta.annotation.Resource private im.tlgxy.cloud.module.storage.service.LocalResumableUploadAccess access;

	@PutMapping("/file/upload/{storageKey}/**")
	@ResponseBody
	@im.tlgxy.cloud.core.annotation.DemoDisable
	public AjaxJson<?> upload(@RequestParam MultipartFile file, @PathVariable("storageKey") String storageKey, @RequestParam(value = "filename", required = false) String filename) throws Exception {
		if (file == null) {
			throw new BizException(ErrorCode.BIZ_UPLOAD_FILE_NOT_EMPTY);
		}

		// 获取上传路径
		String filePath = SpringMvcUtils.getExtractPathWithinPattern();
		if (!filePath.startsWith("/")) filePath = "/" + filePath;
		filePath = filename != null ? filePath + StringUtils.SLASH + filename : filePath;

		AbstractBaseFileService<?> storageServiceByKey = StorageSourceContext.getByStorageKey(storageKey);

		// 如果不是 ProxyTransferService, 则返回错误信息.
		if (storageServiceByKey == null || !Beans.isInstanceOf(storageServiceByKey, AbstractProxyTransferService.class)) {
			return AjaxJson.getError("存储类型异常，不支持上传.");
		}

		// 进行上传.
		AbstractProxyTransferService<?> proxyUploadService = (AbstractProxyTransferService<?>) storageServiceByKey;
		int split = filePath.lastIndexOf('/');
		String parent = split <= 0 ? "/" : filePath.substring(0, split);
		String name = split < 0 ? filePath : filePath.substring(split + 1);
		access.require(im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil.getCurrentUserId(), storageKey, parent, name,
				im.tlgxy.cloud.module.storage.service.UploadDirectoryPassword.decode(
						im.tlgxy.cloud.core.util.RequestHolder.getRequest().getHeader("X-Upload-Directory-Password")));
		try (var input = file.getInputStream()) { proxyUploadService.uploadFile(filePath, input, file.getSize()); }
		return AjaxJson.getSuccess();
	}

}
