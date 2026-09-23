package im.tlgxy.cloud.module.share.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.share.model.request.ShareLinkListRequest;
import im.tlgxy.cloud.module.share.model.result.ShareLinkResult;
import im.tlgxy.cloud.module.share.service.ShareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员分享管理控制器。
 *
 * <p>提供管理员视角的分享管理功能，挂载在 {@code /admin/share} 路径下。
 * 与面向普通用户的 {@link ShareLinkController} 不同，此控制器：
 * <ul>
 *   <li>可以查看系统中所有用户创建的分享记录（不受用户身份限制）</li>
 *   <li>可以清理所有已过期的分享链接（全量操作）</li>
 * </ul>
 * </p>
 *
 * <p>这些接口通常需要管理员权限才能访问，由外层的权限拦截器或过滤器进行控制。</p>
 *
 * @author zhaojun
 */
@Tag(name = "分享管理模块")
@ApiSort(31)
@RestController
@RequestMapping("/admin/share")
public class ShareFileManagerController {

    /**
     * 分享链接服务，提供分享的增删改查等业务逻辑
     */
    @Resource
    private ShareLinkService shareLinkService;

    /**
     * 分页查询分享列表。
     *
     * <p>管理员调用此接口可以查看系统中所有的分享记录，
     * 包括所有用户创建的分享，不受创建者身份的限制。
     * 支持通过 {@link ShareLinkListRequest} 进行分页和条件筛选。</p>
     *
     * @param request 分享列表查询请求参数，包含分页信息、排序方式、筛选条件等
     * @return 包含分页信息（总记录数）和分享记录列表的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "分页查询分享列表", description = "管理员查看所有分享记录")
    @GetMapping("/list")
    public AjaxJson<List<ShareLinkResult>> getShareList(@Valid ShareLinkListRequest request) {
        // 调用服务层获取管理员视角的分享分页数据
        Page<ShareLinkResult> result = shareLinkService.getAdminShareList(request);
        // 将 MyBatis-Plus 分页结果封装为统一的 AjaxJson 分页响应格式
        return AjaxJson.getPageData(result.getTotal(), result.getRecords());
    }

    /**
     * 清理所有已过期的分享链接。
     *
     * <p>管理员调用此接口会删除系统中所有已经超过有效期（过期时间早于当前时间）
     * 的分享记录。这是一个全局清理操作，会影响到所有用户的分享。</p>
     *
     * <p>如果只需要清理当前用户的过期分享，请使用
     * {@link ShareLinkController#deleteExpiredShares()} 方法。</p>
     *
     * @return 包含被删除的过期分享数量的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "清理过期分享", description = "删除所有已过期的分享记录")
    @DeleteMapping("/expired")
    public AjaxJson<Integer> deleteExpiredShares() {
        // 调用服务层删除所有已过期的分享链接，返回被删除的记录数
        int deletedCount = shareLinkService.deleteExpiredLinks();
        // 将删除数量封装为成功的 AjaxJson 响应返回
        return AjaxJson.getSuccessData(deletedCount);
    }
}
