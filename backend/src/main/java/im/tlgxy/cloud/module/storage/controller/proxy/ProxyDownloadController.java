package im.tlgxy.cloud.module.storage.controller.proxy;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.ErrorPageBizException;
import im.tlgxy.cloud.core.util.ProxyDownloadUrlUtils;
import im.tlgxy.cloud.core.util.ShareDownloadTokenUtils;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.module.share.service.ShareLinkFileService;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.SpringMvcUtils;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.beans.Beans;

/**
 * 服务端代理下载 Controller
 *
 * @author zhaojun
 */
@Tag(name = "服务端代理下载")
@ApiSort(6)
@Controller
public class ProxyDownloadController {

    @jakarta.annotation.Resource
    private StorageSourceMapper storageSourceMapper;

    @jakarta.annotation.Resource
    private TenantMapper tenantMapper;

    @jakarta.annotation.Resource
    private ShareLinkFileService shareLinkFileService;

    @GetMapping("/pd/{storageKey}/**")
    @ApiOperationSupport(order = 1)
    @Operation(summary = "下载本地存储源的文件", description ="因第三方存储源都有下载地址，本接口提供本地存储的下载地址的处理, 返回文件流进行下载.")
    @Parameters({
            @Parameter(in = ParameterIn.PATH, name = "storageKey", description = "存储源 key", required = true, schema = @Schema(type = "string")),
            @Parameter(in = ParameterIn.QUERY, name = "type", description = "下载类型", required = true, example = "download", schema = @Schema(type = "string")) // 下载类型: download(不论什么格式的文件都进行下载操作), default(使用浏览器默认处理，浏览器支持预览的格式，则进行预览，不支持的则进行下载)
    })
    @ResponseBody
    public ResponseEntity<Resource> downAttachment(@PathVariable("storageKey") String storageKey, String signature, @RequestParam(value = "filename", required = false) String filename) throws Exception {
        // 获取下载文件路径
        String filePath = SpringMvcUtils.getExtractPathWithinPattern();
        filePath = filename != null ? filePath + StringUtils.SLASH + filename : filePath;

        if (StringUtils.isNotEmpty(filename) && filename.contains(StringUtils.SLASH)) {
            throw new ErrorPageBizException(ErrorCode.BIZ_INVALID_FILE_NAME);
        }

        // 有效签名不应绕过停用状态；直接读取数据库，而非可能仍存活的存储服务缓存。
        // 此检查不依赖登录态，正常的匿名分享/签名下载仍可使用原有下载链接。
        StorageSource storageSource = storageSourceMapper.findByStorageKey(storageKey);
        if (storageSource == null || !Boolean.TRUE.equals(storageSource.getEnable())
                || storageSource.getTenantId() == null) {
            throw new NotFoundAccessException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
        }
        Tenant tenant = tenantMapper.selectById(storageSource.getTenantId());
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) {
            throw new NotFoundAccessException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
        }

        AbstractBaseFileService<?> storageServiceByKey = StorageSourceContext.getByStorageKey(storageKey);

        // 如果不是 ProxyTransferService, 则返回错误信息.
        // todo 判断是否支持代理下载的方式应该是根据存储源设置
        if (!Beans.isInstanceOf(storageServiceByKey, AbstractProxyTransferService.class)) {
            throw new ErrorPageBizException(ErrorCode.BIZ_UNSUPPORTED_PROXY_DOWNLOAD);
        }

        // 进行上传.
        AbstractProxyTransferService<?> proxyDownloadService = (AbstractProxyTransferService<?>) storageServiceByKey;

        // 强制校验签名: 无论是否私有空间, 代理下载都必须携带有效签名,
        // 否则攻击者可在 proxyPrivate=false 时构造任意路径下载其他用户/目录的文件 (#821).
        Integer storageId = proxyDownloadService.getStorageId();
        boolean valid = ProxyDownloadUrlUtils.validSignatureExpired(storageId, filePath, signature);
        if (!valid) {
            throw new ForbiddenAccessException(ErrorCode.BIZ_INVALID_SIGNATURE);
        }

        if (ShareDownloadTokenUtils.isShareSignature(signature)) {
            ShareDownloadTokenUtils.Ticket ticket = ShareDownloadTokenUtils.validate(storageId, filePath, signature);
            if (ticket == null) throw new ForbiddenAccessException(ErrorCode.BIZ_INVALID_SIGNATURE);
            try {
                shareLinkFileService.validateSharedProxyDownload(ticket.shareKey(), storageKey, filePath);
            } catch (BizException denied) {
                throw new ForbiddenAccessException(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION);
            }
        }

        return proxyDownloadService.downloadToStream(filePath);
    }

}
