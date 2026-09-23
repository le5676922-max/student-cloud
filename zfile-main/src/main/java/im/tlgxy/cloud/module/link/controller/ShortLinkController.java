package im.tlgxy.cloud.module.link.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import im.tlgxy.cloud.module.link.model.request.BatchGenerateLinkRequest;
import im.tlgxy.cloud.module.link.model.result.BatchGenerateLinkResponse;
import im.tlgxy.cloud.module.link.service.LinkDownloadService;
import im.tlgxy.cloud.module.link.service.ShortLinkService;
import im.tlgxy.cloud.module.storage.annotation.StoragePermissionCheck;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 短链控制器
 * <p>
 * 负责处理短链（Short Link）相关的请求，包括：
 * <ul>
 *   <li>批量生成短链：对指定存储源的多个文件路径批量生成短链</li>
 *   <li>短链跳转：根据短链 key 进行 302 重定向到对应的文件下载地址</li>
 * </ul>
 * <p>
 * 短链格式为：{domain}/s/{shortKey}，通过短链可以隐藏真实的文件路径，
 * 同时支持设置过期时间来控制链接的有效期。
 *
 * @author zhaojun
 */
@Tag(name = "直短链模块")
@ApiSort(5)
@Controller
@Slf4j
public class ShortLinkController {

    /**
     * 系统配置服务，用于获取站点域名等配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 短链服务，处理短链的生成、查询等业务逻辑
     */
    @Resource
    private ShortLinkService shortLinkService;

    /**
     * 存储源服务，用于根据存储源 key 查找对应的存储源 ID
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 链接下载服务，处理短链跳转后的实际下载逻辑
     */
    @Resource
    private LinkDownloadService linkDownloadService;

    /**
     * 批量生成短链
     * <p>
     * 对指定存储源的多个文件路径批量生成短链地址。
     * 生成过程中会为每个文件路径创建唯一的短链 key，并设置过期时间。
     * 需要用户具有对应存储源的短链生成权限（{@link FileOperatorTypeEnum#SHORT_LINK}）。
     *
     * @param   batchGenerateLinkRequest
     *          批量生成短链请求参数，包含存储源 key、文件路径列表和过期时间
     *
     * @return  AjaxJson 包含批量生成的短链地址列表
     */
    @PostMapping("/api/short-link/batch/generate")
    @ResponseBody
    @ApiOperationSupport(order = 1)
    @Operation(summary = "生成短链", description ="对指定存储源的某文件路径生成短链")
    @StoragePermissionCheck(action = FileOperatorTypeEnum.SHORT_LINK)
    public AjaxJson<List<BatchGenerateLinkResponse>> generatorShortLink(@RequestBody @Valid BatchGenerateLinkRequest batchGenerateLinkRequest) {
        List<BatchGenerateLinkResponse> result = new ArrayList<>();

        // 获取站点域名
        String domain = systemConfigService.getAxiosFromDomainOrSetting();
        Long expireTime = batchGenerateLinkRequest.getExpireTime();
        String storageKey = batchGenerateLinkRequest.getStorageKey();
        Integer storageId = storageSourceService.findIdByKey(storageKey);

        // 遍历每个文件路径，生成对应的短链
        for (String path : batchGenerateLinkRequest.getPaths()) {
            // 拼接全路径地址: 用户基础路径 + 文件路径
            String currentUserBasePath = StorageSourceContext.getByStorageId(storageId).getCurrentUserBasePath();
            String fullPath = StringUtils.concat(currentUserBasePath, path);
            // 生成短链
            ShortLink shortLink = shortLinkService.generatorShortLink(storageId, fullPath, expireTime);
            // 拼接完整短链 URL: domain + /s/ + shortKey
            String shortUrl = StringUtils.removeDuplicateSlashes(domain + "/s/" + shortLink.getShortKey());
            result.add(new BatchGenerateLinkResponse(shortUrl));
        }
        return AjaxJson.getSuccessData(result);
    }


    /**
     * 短链跳转处理
     * <p>
     * 根据短链 key 查找对应的原始文件路径，然后通过 302 重定向跳转到文件的实际下载地址。
     * 该方法会经过 Referer 防盗链和访问频率限制的校验。
     *
     * @param   key
     *          短链 key，用于唯一标识一个短链
     *
     * @return  ResponseEntity 包含 302 重定向响应或错误信息
     *
     * @throws IOException 处理下载过程中可能出现的 IO 异常
     */
    @GetMapping("/s/{key}")
    @ApiOperationSupport(order = 2)
    @Operation(summary = "跳转短链", description ="根据短链 key 跳转（302 重定向）到对应的直链.")
    @Parameter(in = ParameterIn.PATH, name = "key", description = "短链 key", required = true, schema = @Schema(type = "string"))
    public ResponseEntity<?> parseShortKey(@PathVariable String key) throws IOException {
        return linkDownloadService.handlerShortLink(key);
    }

}