package im.tlgxy.cloud.module.link.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.SpringMvcUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.model.request.BatchGenerateLinkRequest;
import im.tlgxy.cloud.module.link.model.result.BatchGenerateLinkResponse;
import im.tlgxy.cloud.module.link.service.DynamicDirectLinkPrefixService;
import im.tlgxy.cloud.module.link.service.LinkDownloadService;
import im.tlgxy.cloud.module.storage.annotation.StoragePermissionCheck;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 直链控制器
 * <p>
 * 负责处理与直链（Direct Link）相关的请求，包括：
 * <ul>
 *   <li>动态注册直链前缀路径映射，支持用户自定义直链 URL 前缀</li>
 *   <li>处理直链下载请求，根据 URL 中的存储源 key 和文件路径跳转到对应的短链或直接下载</li>
 *   <li>批量生成路径直链</li>
 * </ul>
 * <p>
 * 该控制器在应用启动就绪事件（{@link ApplicationReadyEvent}）时，会动态注册直链路径映射，
 * 使直链前缀可以在系统配置中动态修改。
 *
 * @author zhaojun
 */
@Tag(name = "短链")
@ApiSort(5)
@Controller
@Slf4j
public class DirectLinkController {

    /**
     * 链接下载服务，处理实际的下载逻辑
     */
    @Resource
    private LinkDownloadService linkDownloadService;

    /**
     * 系统配置服务，获取直链前缀等配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 动态直链前缀服务，用于注册和更新动态路由映射
     */
    @Resource
    private DynamicDirectLinkPrefixService dynamicDirectLinkPrefixService;

    /**
     * 直链路径后缀模板，格式为 /{storageKey}/**
     */
    public static final String DIRECT_LINK_SUFFIX_PATH = "/{storageKey}/**";

    /**
     * 应用启动就绪后初始化直链路由映射
     * <p>
     * 监听 {@link ApplicationReadyEvent} 事件，在应用完全启动后，
     * 从系统配置中读取直链前缀，动态注册直链的 URL 映射。
     * 这样可以根据用户配置的直链前缀来响应不同的 URL 请求。
     *
     * @throws NoSuchMethodException 如果反射获取 directLink 方法失败
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() throws NoSuchMethodException {
        // 获取配置的直链前缀
        String directLinkPrefix = systemConfigService.getSystemConfig().getDirectLinkPrefix();
        // 通过反射获取直链处理方法
        Method directLinkMethod = DirectLinkController.class.getMethod("directLink", String.class);
        // 构建请求映射信息并注册到 Spring MVC 中
        RequestMappingInfo requestMappingInfo = RequestMappingInfo.paths(directLinkPrefix + DIRECT_LINK_SUFFIX_PATH).build();
        dynamicDirectLinkPrefixService.registerMappingHandlerMapping(SystemConfig.DIRECT_LINK_PREFIX_NAME, requestMappingInfo, this, directLinkMethod);
    }

    /**
     * 路径直链处理方法
     * <p>
     * 根据 URL 中的存储源 key 和文件路径，获取到对应文件。
     * 该方法会被动态注册为请求映射，处理用户的直链下载请求。
     *
     * @param   storageKey
     *          存储源 key，用于标识具体的存储源
     *
     * @return  ResponseEntity 包含下载响应，可能是 302 重定向到下载地址或直接返回文件内容
     *
     * @throws IOException 处理下载过程中可能出现的 IO 异常
     */
    public ResponseEntity<?> directLink(@PathVariable("storageKey") String storageKey) throws IOException {
        // 获取直链全路径
        String filePath = SpringMvcUtils.getExtractPathWithinPattern();

        // 如果路径不是以 / 开头, 则补充上
        if (StringUtils.isNotEmpty(filePath) && filePath.charAt(0) != StringUtils.SLASH_CHAR) {
            filePath = StringUtils.SLASH + filePath;
        }

        return linkDownloadService.handlerDirectLink(storageKey, filePath);
    }

    /**
     * 批量生成路径直链
     * <p>
     * 对指定存储源的多个文件路径批量生成直链地址。
     * 需要用户具有对应存储源的直链生成权限（{@link FileOperatorTypeEnum#LINK}）。
     *
     * @param   batchGenerateLinkRequest
     *          批量生成直链请求参数，包含存储源 key 和文件路径列表
     *
     * @return  AjaxJson 包含批量生成的直链地址列表
     */
    @PostMapping("/api/path-link/batch/generate")
    @ResponseBody
    @ApiOperationSupport(order = 1)
    @Operation(summary = "生成路径直链", description ="对指定存储源的某文件路径生成路径直链")
    @StoragePermissionCheck(action = FileOperatorTypeEnum.LINK)
    public AjaxJson<List<BatchGenerateLinkResponse>> generatorShortLink(@RequestBody @Valid BatchGenerateLinkRequest batchGenerateLinkRequest) {
        List<BatchGenerateLinkResponse> result = new ArrayList<>();

        // 获取站点域名
        String serverAddress = systemConfigService.getAxiosFromDomainOrSetting();
        String directLinkPrefix = systemConfigService.getSystemConfig().getDirectLinkPrefix();

        String storageKey = batchGenerateLinkRequest.getStorageKey();

        // 获取存储源服务实例
        AbstractBaseFileService<?> baseFileService = StorageSourceContext.getByStorageKey(storageKey);
        if (baseFileService == null) {
            throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
        }
        String currentUserBasePath = baseFileService.getCurrentUserBasePath();

        // 遍历每个文件路径，拼接完整的直链地址
        for (String path : batchGenerateLinkRequest.getPaths()) {
            // 拼接全路径地址.
            String fullPath = StringUtils.concat(serverAddress, directLinkPrefix, storageKey, currentUserBasePath, path);
            result.add(new BatchGenerateLinkResponse(fullPath));
        }
        return AjaxJson.getSuccessData(result);
    }
}