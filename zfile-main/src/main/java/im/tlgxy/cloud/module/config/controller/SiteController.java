package im.tlgxy.cloud.module.config.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.model.result.FrontSiteConfigResult;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.storage.annotation.ProCheck;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.request.base.FileListConfigRequest;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceConfigResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * 面向前台（用户端）的站点基础模块接口。
 * <p>
 * 提供前台页面所需的全局配置、存储源配置以及用户根路径等信息的查询接口。
 * 与 {@link SettingController} 不同，本控制器不需要管理员权限，面向前台所有用户。
 *
 * @author zhaojun
 */
@Tag(name = "站点基础模块")
@ApiSort(1)
@Slf4j
@RequestMapping("/api/site")
@RestController
public class SiteController {

    /**
     * TlgxyCloud 全局属性配置
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 存储源服务，用于获取存储源的配置信息
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 系统配置服务，用于读取系统全局配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 获取前台站点全局设置。
     * <p>
     * 返回数据包含页面布局、列表尺寸、公告、配置信息，以及调试模式标志和访客模式判断。
     * 如果当前是访客用户且配置了访客首页，则 guest 字段为 true。
     *
     * @return 包含前台站点配置结果的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取站点全局设置", description = "获取站点全局设置, 包括是否页面布局、列表尺寸、公告、配置信息")
    @GetMapping("/config/global")
    @ProCheck
    public AjaxJson<FrontSiteConfigResult> globalConfig() {
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();

        FrontSiteConfigResult frontSiteConfigResult = new FrontSiteConfigResult();
        BeanUtils.copyProperties(systemConfig, frontSiteConfigResult);

        // 设置调试模式标志
        frontSiteConfigResult.setDebugMode(tlgxyCloudProperties.isDebug());
        // 判断当前用户是否为访客
        boolean guestUser = Objects.equals(TlgxyCloudAuthUtil.getCurrentUserId(), UserConstant.ANONYMOUS_ID);
        // 判断是否配置了访客首页内容
        boolean guestIndexNotBlank = StringUtils.isNotBlank(systemConfig.getGuestIndexHtml());
        // 如果是访客且配置了访客首页，则标记为访客模式
        frontSiteConfigResult.setGuest(guestUser && guestIndexNotBlank);
        return AjaxJson.getSuccessData(frontSiteConfigResult);
    }

    /**
     * 获取指定存储源的配置信息。
     * <p>
     * 返回存储源的启用状态、名称、类型以及具体配置项。
     *
     * @param fileListConfigRequest 包含存储源 key 和路径的请求参数
     * @return 包含存储源配置结果的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "获取存储源设置", description = "获取某个存储源的设置信息, 包括是否启用, 名称, 存储源类型, 存储源配置信息")
    @PostMapping("/config/storage")
    public AjaxJson<StorageSourceConfigResult> storageList(@Valid @RequestBody FileListConfigRequest fileListConfigRequest) {
        StorageSourceConfigResult storageSourceConfigResult = storageSourceService.getStorageConfigSource(fileListConfigRequest);
        return AjaxJson.getSuccessData(storageSourceConfigResult);
    }

    /**
     * 获取当前用户在指定存储源中的根路径。
     * <p>
     * 如果存储源不存在或当前用户没有配置基础路径，则返回空字符串。
     *
     * @param storageKey 存储源的唯一标识 key
     * @return 包含用户根路径字符串的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "获取用户存储源路径", description = "获取用户存储源路径")
    @GetMapping("/config/userRootPath/{storageKey}")
    public AjaxJson<String> getUserRootPath(@PathVariable("storageKey") String storageKey) {
        // 根据存储源 key 获取对应的文件服务实例
        AbstractBaseFileService<?> baseFileService = StorageSourceContext.getByStorageKey(storageKey);
        // 存储源不存在或未配置用户基础路径时返回空字符串
        if (baseFileService == null || baseFileService.getCurrentUserBasePath() == null) {
            return AjaxJson.getSuccessData("");
        }
        return AjaxJson.getSuccessData(baseFileService.getCurrentUserBasePath());
    }

}