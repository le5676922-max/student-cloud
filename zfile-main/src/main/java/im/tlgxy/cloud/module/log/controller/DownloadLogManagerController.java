package im.tlgxy.cloud.module.log.controller;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.model.request.BatchDeleteRequest;
import im.tlgxy.cloud.module.link.model.request.QueryDownloadLogRequest;
import im.tlgxy.cloud.module.log.convert.DownloadLogConvert;
import im.tlgxy.cloud.module.log.model.entity.DownloadLog;
import im.tlgxy.cloud.module.log.model.result.DownloadLogResult;
import im.tlgxy.cloud.module.log.service.DownloadLogService;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 直链下载日志管理控制器
 * <p>
 * 提供直链下载日志的分页查询、单条删除、批量删除以及按条件批量删除等管理功能。
 * 所有接口均以 /admin/download/log 为前缀，供后台管理端使用。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "直链日志管理")
@ApiSort(7)
@Controller
@RequestMapping("/admin/download/log")
public class DownloadLogManagerController {

    /**
     * 存储源服务，用于根据存储源 key 查询存储源信息
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 下载日志实体转换器，用于将 DownloadLog 实体转换为 DownloadLogResult 结果对象
     */
    @Resource
    private DownloadLogConvert downloadLogConvert;

    /**
     * 下载日志服务，封装了下载日志的增删改查操作
     */
    @Resource
    private DownloadLogService downloadLogService;

    /**
     * 系统配置服务，用于获取系统级别的配置信息（如服务器地址、直链前缀等）
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 分页查询直链下载日志列表
     * <p>
     * 支持按存储源、路径、链接类型、短链key、IP、Referer、User-Agent、时间范围等条件进行筛选，
     * 并支持指定排序字段和排序方向。
     * </p>
     *
     * @param queryDownloadLogRequest 查询下载日志的请求参数，包含分页、排序及各种筛选条件
     * @return 包含分页数据和日志结果流的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 1)
    @GetMapping("/list")
    @Operation(summary = "直链下载日志")
    @ResponseBody
    public AjaxJson<Stream<DownloadLogResult>> list(QueryDownloadLogRequest queryDownloadLogRequest) {
        // 根据请求参数构建排序规则，支持升序(asc)和降序(desc)
        boolean asc = Objects.equals(queryDownloadLogRequest.getOrderDirection(), "asc");
        OrderItem orderItem = asc ? OrderItem.asc(queryDownloadLogRequest.getOrderBy()) : OrderItem.desc(queryDownloadLogRequest.getOrderBy());
        // 创建分页对象，设置页码、每页条数和排序规则
        Page<DownloadLog> pages = new Page<DownloadLog>(queryDownloadLogRequest.getPage(), queryDownloadLogRequest.getLimit())
                .addOrder(orderItem);

        // 构建动态查询条件，所有条件均为可选，只有参数不为空时才参与查询
        LambdaQueryWrapper<DownloadLog> queryWrapper = new LambdaQueryWrapper<DownloadLog>()
                .eq(StringUtils.isNotEmpty(queryDownloadLogRequest.getStorageKey()), DownloadLog::getStorageKey, queryDownloadLogRequest.getStorageKey())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getPath()), DownloadLog::getPath, queryDownloadLogRequest.getPath())
                // linkType 为 "shortLink" 时查询短链记录（shortKey 不为空），为 "directLink" 时查询直链记录（shortKey 为空）
                .isNotNull("shortLink".equals(queryDownloadLogRequest.getLinkType()), DownloadLog::getShortKey)
                .isNull("directLink".equals(queryDownloadLogRequest.getLinkType()), DownloadLog::getShortKey)
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getShortKey()), DownloadLog::getShortKey, queryDownloadLogRequest.getShortKey())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getIp()), DownloadLog::getIp, queryDownloadLogRequest.getIp())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getReferer()), DownloadLog::getReferer, queryDownloadLogRequest.getReferer())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getUserAgent()), DownloadLog::getUserAgent, queryDownloadLogRequest.getUserAgent())
                .ge(ObjUtil.isNotEmpty(queryDownloadLogRequest.getDateFrom()), DownloadLog::getCreateTime, queryDownloadLogRequest.getDateFrom())
                .le(ObjUtil.isNotEmpty(queryDownloadLogRequest.getDateTo()), DownloadLog::getCreateTime, queryDownloadLogRequest.getDateTo());

        // 执行分页查询
        Page<DownloadLog> selectResult = downloadLogService.selectPage(pages, queryWrapper);

        // 缓存已查询过的存储源信息，避免重复查询数据库
        Map<String, StorageSource> cache = new HashMap<>();

        // 获取服务器地址和直链前缀，用于拼接完整的下载链接
        String serverAddress = systemConfigService.getAxiosFromDomainOrSetting();
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String directLinkPrefix = systemConfig.getDirectLinkPrefix();

        // 将每个日志实体转换为结果对象，并拼接短链/直链地址
        Stream<DownloadLogResult> shortLinkResultList = selectResult.getRecords().stream().map(model -> {
            String storageKey = model.getStorageKey();

            // 使用 computeIfAbsent 缓存存储源信息，避免重复查询
            StorageSource storageSource = cache.computeIfAbsent(storageKey, (key) -> storageSourceService.findByStorageKey(key));
            DownloadLogResult downloadLogResult = downloadLogConvert.entityToResultList(model, storageSource);

            // 如果有短链 key，则拼接短链地址；否则拼接直链地址
            if (StringUtils.isNotBlank(downloadLogResult.getShortKey())) {
                downloadLogResult.setShortLink(StringUtils.concat(serverAddress, "s", downloadLogResult.getShortKey()));
            } else {
                downloadLogResult.setPathLink(StringUtils.concat(serverAddress, directLinkPrefix, downloadLogResult.getStorageKey(), downloadLogResult.getPath()));
            }

            return downloadLogResult;
        });
        // 返回分页数据，包括总记录数
        return AjaxJson.getPageData(selectResult.getTotal(), shortLinkResultList);
    }


    /**
     * 根据 ID 删除单条直链下载日志
     *
     * @param id 要删除的日志 ID
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 2)
    @DeleteMapping("/delete/{id}")
    @Operation(summary = "删除直链")
    @Parameter(in = ParameterIn.PATH, name = "id", description = "直链 id", required = true, schema = @Schema(type = "integer"))
    @ResponseBody
    @DemoDisable
    public AjaxJson<Void> deleteById(@PathVariable Integer id) {
        downloadLogService.removeById(id);
        return AjaxJson.getSuccess();
    }


    /**
     * 批量删除直链下载日志
     *
     * @param batchDeleteRequest 包含要删除的 ID 列表的请求对象
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 3)
    @PostMapping("/delete/batch")
    @ResponseBody
    @Operation(summary = "批量删除直链")
    @DemoDisable
    public AjaxJson<Void> batchDelete(@RequestBody BatchDeleteRequest batchDeleteRequest) {
        List<Integer> ids = batchDeleteRequest.getIds();
        downloadLogService.removeBatchByIds(ids);
        return AjaxJson.getSuccess();
    }

    /**
     * 根据查询条件批量删除直链下载日志
     * <p>
     * 与查询接口使用相同的筛选条件，删除所有匹配的日志记录。
     * </p>
     *
     * @param queryDownloadLogRequest 查询条件请求参数
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 4)
    @PostMapping("/delete/batch/query")
    @ResponseBody
    @Operation(summary = "根据查询条件批量删除直链")
    @DemoDisable
    public AjaxJson<Void> batchDeleteBySearchParams(@RequestBody QueryDownloadLogRequest queryDownloadLogRequest) {

        // 构建与查询接口一致的动态筛选条件
        LambdaQueryWrapper<DownloadLog> queryWrapper = new LambdaQueryWrapper<DownloadLog>()
                .eq(StringUtils.isNotEmpty(queryDownloadLogRequest.getStorageKey()), DownloadLog::getStorageKey, queryDownloadLogRequest.getStorageKey())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getPath()), DownloadLog::getPath, queryDownloadLogRequest.getPath())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getShortKey()), DownloadLog::getShortKey, queryDownloadLogRequest.getShortKey())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getIp()), DownloadLog::getIp, queryDownloadLogRequest.getIp())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getReferer()), DownloadLog::getReferer, queryDownloadLogRequest.getReferer())
                .like(StringUtils.isNotEmpty(queryDownloadLogRequest.getUserAgent()), DownloadLog::getUserAgent, queryDownloadLogRequest.getUserAgent())
                .ge(ObjUtil.isNotEmpty(queryDownloadLogRequest.getDateFrom()), DownloadLog::getCreateTime, queryDownloadLogRequest.getDateFrom())
                .le(ObjUtil.isNotEmpty(queryDownloadLogRequest.getDateTo()), DownloadLog::getCreateTime, queryDownloadLogRequest.getDateTo());

        // 根据查询条件删除所有匹配的记录
        downloadLogService.deleteByQueryWrapper(queryWrapper);
        return AjaxJson.getSuccess();
    }

}