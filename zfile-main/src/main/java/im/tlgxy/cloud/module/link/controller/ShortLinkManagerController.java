package im.tlgxy.cloud.module.link.controller;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.cache.LinkRateLimiterCache;
import im.tlgxy.cloud.module.link.convert.ShortLinkConvert;
import im.tlgxy.cloud.module.link.model.dto.CacheInfo;
import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import im.tlgxy.cloud.module.link.model.request.BatchDeleteRequest;
import im.tlgxy.cloud.module.link.model.request.QueryShortLinkLogRequest;
import im.tlgxy.cloud.module.link.model.request.ShortLinkResult;
import im.tlgxy.cloud.module.link.service.ShortLinkService;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 直链/短链管理控制器
 * <p>
 * 提供后台管理系统中对直链/短链的增删改查操作，包括：
 * <ul>
 *   <li>分页搜索短链列表，支持按存储源、短链 key、文件 URL、创建时间等条件筛选</li>
 *   <li>单个删除短链</li>
 *   <li>批量删除短链</li>
 *   <li>导出短链数据为 Excel 文件</li>
 *   <li>获取直链访问频率限制信息（查看当前被限流的 IP 列表）</li>
 *   <li>删除所有已过期的短链</li>
 * </ul>
 * <p>
 * 所有接口的 URL 前缀为 /admin，属于后台管理接口，需要管理员权限。
 *
 * @author zhaojun
 */
@Tag(name = "直链管理")
@ApiSort(7)
@Controller
@RequestMapping("/admin")
public class ShortLinkManagerController {

    /**
     * 系统配置服务，用于获取站点域名等配置信息
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 短链服务，处理短链的增删改查业务逻辑
     */
    @Resource
    private ShortLinkService shortLinkService;

    /**
     * 存储源服务，用于查询存储源信息
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 短链实体转换器，用于将实体转换为前端展示的 DTO
     */
    @Resource
    private ShortLinkConvert shortLinkConvert;

    /**
     * 链接频率限制缓存，用于查询当前的限流状态
     */
    @Resource
    private LinkRateLimiterCache linkRateLimiterCache;


    /**
     * 搜索短链列表
     * <p>
     * 支持分页和多条件筛选查询短链数据，返回的结果中包含拼接好的完整短链地址。
     *
     * @param   queryObj
     *          查询条件对象，包含分页参数和筛选条件
     *
     * @return  AjaxJson 包含分页的短链结果列表
     */
    @ApiOperationSupport(order = 1)
    @GetMapping("/link/list")
    @Operation(summary = "搜索短链")
    @ResponseBody
    public AjaxJson<List<ShortLinkResult>> list(QueryShortLinkLogRequest queryObj) {
        // 执行分页查询
        Page<ShortLinkResult> resultPage = getShortLinkResultPage(queryObj);

        // 获取站点域名，用于拼接完整短链地址
        String serverAddress = systemConfigService.getAxiosFromDomainOrSetting();

        // 为每条记录拼接完整的短链 URL
        resultPage.getRecords().forEach(shortLinkResult -> {
            shortLinkResult.setShortLink(StringUtils.concat(serverAddress, "s", shortLinkResult.getShortKey()));
        });

        return AjaxJson.getPageData(resultPage.getTotal(), resultPage.getRecords());
    }

    /**
     * 删除单个短链
     * <p>
     * 根据短链 ID 删除指定的短链记录。标注了 {@link DemoDisable} 注解，在演示模式下禁用。
     *
     * @param   id
     *          要删除的短链 ID
     *
     * @return  AjaxJson 操作结果
     */
    @ApiOperationSupport(order = 2)
    @DeleteMapping("/link/delete/{id}")
    @Operation(summary = "删除短链")
    @Parameter(in = ParameterIn.PATH, name = "id", description = "短链 id", required = true, schema = @Schema(type = "integer"))
    @ResponseBody
    @DemoDisable
    public AjaxJson<Void> deleteById(@PathVariable Integer id) {
        shortLinkService.removeById(id);
        return AjaxJson.getSuccess();
    }


    /**
     * 批量删除短链
     * <p>
     * 根据 ID 列表批量删除多条短链记录。标注了 {@link DemoDisable} 注解，在演示模式下禁用。
     *
     * @param   batchDeleteRequest
     *          批量删除请求参数，包含要删除的 ID 列表
     *
     * @return  AjaxJson 操作结果
     */
    @ApiOperationSupport(order = 3)
    @PostMapping("/link/delete/batch")
    @ResponseBody
    @Operation(summary = "批量删除短链")
    @DemoDisable
    public AjaxJson<Void> batchDelete(@RequestBody BatchDeleteRequest batchDeleteRequest) {
        shortLinkService.removeBatchByIds(batchDeleteRequest.getIds());
        return AjaxJson.getSuccess();
    }

    /**
     * 导出短链数据为 Excel 文件
     * <p>
     * 根据查询条件导出短链数据到 Excel 文件，包含 ID、存储源名称、存储源类型、
     * 短链 key、文件路径、创建时间和过期时间等列。
     *
     * @param   queryObj
     *          查询条件对象，用于筛选要导出的数据
     *
     * @param   response
     *          HTTP 响应对象，用于输出 Excel 文件流
     *
     * @throws IOException 写入响应流时可能出现的 IO 异常
     */
    @ApiOperationSupport(order = 4)
    @GetMapping("/link/export")
    @ResponseBody
    @Operation(summary = "导出短链")
    public void exportExcel(QueryShortLinkLogRequest queryObj, HttpServletResponse response) throws IOException {
        // 执行分页查询获取数据
        Page<ShortLinkResult> shortLinkResultPage = getShortLinkResultPage(queryObj);

        // 创建 Excel 写入器并设置列别名
        ExcelWriter writer = ExcelUtil.getWriter(true);
        writer.addHeaderAlias("id", "ID");
        writer.addHeaderAlias("storageName", "存储源名称");
        writer.addHeaderAlias("storageTypeStr", "存储源类型");
        writer.addHeaderAlias("shortKey", "短链 key");
        writer.addHeaderAlias("url", "文件路径");
        writer.addHeaderAlias("createDate", "创建时间");
        writer.addHeaderAlias("expireDate", "过期时间");
        writer.setOnlyAlias(true);

        // 写入数据并设置列宽
        writer.write(shortLinkResultPage.getRecords(), true);
        writer.setColumnWidth(0, 8);
        writer.setColumnWidth(1, 30);
        writer.setColumnWidth(2, 15);
        writer.setColumnWidth(3, 15);
        writer.setColumnWidth(4, 50);
        writer.setColumnWidth(5, 15);
        writer.setColumnWidth(6, 15);

        // 设置响应类型并输出 Excel 文件流
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        ServletOutputStream out=response.getOutputStream();

        writer.flush(out, true);
        writer.close();
        IoUtil.close(out);
    }


    /**
     * 获取直链访问限制信息
     * <p>
     * 查询当前被限流的客户端 IP 列表及其访问计数信息，用于管理员监控直链访问状态。
     *
     * @return  AjaxJson 包含缓存中的限流信息列表
     */
    @ApiOperationSupport(order = 5)
    @GetMapping("/link/limit/info")
    @ResponseBody
    @Operation(summary = "获取直链访问限制信息")
    public AjaxJson<List<CacheInfo<String, AtomicInteger>>> getLinkLimitInfo() {
        return AjaxJson.getSuccessData(linkRateLimiterCache.getCacheInfo());
    }

    /**
     * 根据查询条件执行短链分页查询
     * <p>
     * 该私有方法封装了短链查询的完整逻辑：
     * <ol>
     *   <li>根据请求参数构建分页和排序条件</li>
     *   <li>构建动态查询条件（支持按存储源、短链 key、文件 URL、创建时间等筛选）</li>
     *   <li>执行数据库查询</li>
     *   <li>将查询结果转换为前端展示的 DTO 列表</li>
     * </ol>
     *
     * @param   queryObj
     *          查询条件对象，包含分页、排序和筛选参数
     *
     * @return  分页的短链结果数据
     */
    @NotNull
    private Page<ShortLinkResult> getShortLinkResultPage(QueryShortLinkLogRequest queryObj) {
        // 分页和排序
        boolean asc = Objects.equals(queryObj.getOrderDirection(), "asc");
        OrderItem orderItem = asc ? OrderItem.asc(queryObj.getOrderBy()) : OrderItem.desc(queryObj.getOrderBy());
        Page<ShortLink> pages = new Page<ShortLink>(queryObj.getPage(), queryObj.getLimit())
                .addOrder(orderItem);

        // 搜索条件
        LambdaQueryWrapper<ShortLink> queryWrapper = new LambdaQueryWrapper<ShortLink>()
                .eq(StringUtils.isNotEmpty(queryObj.getStorageId()), ShortLink::getStorageId, queryObj.getStorageId())
                .like(StringUtils.isNotEmpty(queryObj.getKey()), ShortLink::getShortKey, queryObj.getKey())
                .like(StringUtils.isNotEmpty(queryObj.getUrl()), ShortLink::getUrl, queryObj.getUrl())
                .ge(ObjUtil.isNotEmpty(queryObj.getDateFrom()), ShortLink::getCreateDate, queryObj.getDateFrom())
                .le(ObjUtil.isNotEmpty(queryObj.getDateTo()), ShortLink::getCreateDate, queryObj.getDateTo());

        // 执行查询
        Page<ShortLink> selectResult = shortLinkService.selectPage(pages, queryWrapper);

        // 转换为结果集
        Map<Integer, StorageSource> cache = new HashMap<>();
        Stream<ShortLinkResult> shortLinkResultList = selectResult.getRecords().stream().map(shortLink -> {
            Integer shortLinkStorageId = shortLink.getStorageId();

            // 使用缓存避免重复查询同一个存储源
            StorageSource storageSource = cache.getOrDefault(shortLinkStorageId, storageSourceService.findById(shortLinkStorageId));
            cache.put(shortLinkStorageId, storageSource);
            return shortLinkConvert.entityToResultList(shortLink, storageSource);
        });

        // 构建分页结果
        Page<ShortLinkResult> resultPage = new Page<>();
        resultPage.setTotal(selectResult.getTotal());
        resultPage.setRecords(shortLinkResultList.collect(Collectors.toList()));
        return resultPage;
    }

    /**
     * 删除所有已过期的短链
     * <p>
     * 清理数据库中所有过期时间早于当前时间的短链记录。
     * 标注了 {@link DemoDisable} 注解，在演示模式下禁用。
     *
     * @return  AjaxJson 包含被删除的短链数量
     */
    @ApiOperationSupport(order = 6)
    @DeleteMapping("/link/deleteExpireLink")
    @Operation(summary = "删除过期短链")
    @ResponseBody
    @DemoDisable
    public AjaxJson<Integer> deleteExpireLink() {
        int updateRows = shortLinkService.deleteExpireLink();
        return AjaxJson.getSuccessData(updateRows);
    }

}