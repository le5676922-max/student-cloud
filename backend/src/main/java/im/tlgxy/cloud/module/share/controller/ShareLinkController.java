package im.tlgxy.cloud.module.share.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import im.tlgxy.cloud.module.onlyoffice.service.OnlyOfficeConfigService;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.share.model.entity.ShareLink;
import im.tlgxy.cloud.module.share.model.request.CreateShareLinkRequest;
import im.tlgxy.cloud.module.share.model.request.ShareFileListRequest;
import im.tlgxy.cloud.module.share.model.request.ShareLinkListRequest;
import im.tlgxy.cloud.module.share.model.request.ShareOnlyOfficeConfigTokenRequest;
import im.tlgxy.cloud.module.share.model.request.VerifySharePasswordRequest;
import im.tlgxy.cloud.module.share.model.result.CreateShareLinkResult;
import im.tlgxy.cloud.module.share.model.result.ShareFileInfoResult;
import im.tlgxy.cloud.module.share.model.result.ShareLinkResult;
import im.tlgxy.cloud.module.share.service.ShareLinkFileService;
import im.tlgxy.cloud.module.share.service.ShareLinkService;
import im.tlgxy.cloud.module.storage.annotation.StoragePermissionCheck;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分享文件控制器。
 *
 * <p>面向终端用户的分享功能接口，挂载在 {@code /api/share} 路径下。
 * 提供分享链接的创建、查看、密码验证、文件浏览、在线预览（OnlyOffice）、
 * 下载、删除以及用户个人分享列表管理等功能。</p>
 *
 * <p>该控制器是分享模块的核心入口，协调 {@link ShareLinkService}（分享链接管理）、
 * {@link ShareLinkFileService}（分享文件操作）和 {@link OnlyOfficeConfigService}
 * （在线预览）三个服务完成分享场景下的完整业务流程。</p>
 *
 * <p>与管理员控制器 {@link ShareFileManagerController} 的区别：
 * <ul>
 *   <li>本控制器面向普通用户，操作范围受用户身份限制</li>
 *   <li>管理员控制器可以查看和管理所有用户的分享记录</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "分享文件模块")
@ApiSort(3)
@Slf4j
@RequestMapping("/api/share")
@RestController
public class ShareLinkController {

    /**
     * 分享链接服务，负责分享链接的创建、查询、验证、删除等核心业务逻辑
     */
    @Resource
    private ShareLinkService shareLinkService;

    /**
     * 分享文件服务，负责分享场景下的文件列表获取、文件下载地址生成等文件操作
     */
    @Resource
    private ShareLinkFileService shareLinkFileService;

    /**
     * OnlyOffice 配置服务，负责生成 OnlyOffice 在线预览所需的配置令牌和参数
     */
    @Resource
    private OnlyOfficeConfigService onlyOfficeConfigService;

    /**
     * 创建分享链接。
     *
     * <p>根据用户指定的文件或文件夹路径、过期时间、密码等参数创建一个新的分享链接。
     * 创建前会通过 {@link StoragePermissionCheck} 注解校验当前用户对目标文件/文件夹
     * 是否具有分享权限。</p>
     *
     * @param request 创建分享链接的请求参数，包含存储源 key、文件路径、过期时间、密码等
     * @return 包含新创建的分享链接信息（分享 key、URL 等）的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "创建分享链接", description = "根据指定的文件或文件夹创建分享链接")
    @PostMapping("/create")
    @StoragePermissionCheck(action = FileOperatorTypeEnum.SHARE_LINK)
    public AjaxJson<CreateShareLinkResult> createShareLink(@Valid @RequestBody CreateShareLinkRequest request) {
        // 调用服务层创建分享链接，生成唯一的分享 key 和访问 URL
        CreateShareLinkResult result = shareLinkService.createShareLink(request);
        return AjaxJson.getSuccessData(result);
    }

    /**
     * 获取分享基本信息。
     *
     * <p>根据分享链接的唯一标识 key 获取分享的基本信息（如分享的文件名、创建时间、
     * 过期时间、是否需要密码等），此接口无需密码验证即可调用，用于前端展示
     * 分享的基本信息页面。如果需要查看具体的文件列表，则需要先验证密码。</p>
     *
     * @param shareKey 分享链接的唯一标识 key，从 URL 路径中获取
     * @return 包含分享基本信息的 AjaxJson 响应，如果分享不存在或已过期则返回错误
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "获取分享信息", description = "根据分享链接key获取分享的基本信息，无需密码")
    @GetMapping("/info/{shareKey}")
    @Parameter(in = ParameterIn.PATH, name = "shareKey", description = "分享链接key", required = true, schema = @Schema(type = "string"))
    public AjaxJson<ShareLinkResult> getShareInfo(@PathVariable String shareKey) {
        // 调用服务层根据 shareKey 查询分享的基本信息
        ShareLinkResult result = shareLinkService.getShareLinkInfo(shareKey);
        return AjaxJson.getSuccessData(result);
    }

    /**
     * 验证分享密码。
     *
     * <p>在访问受密码保护的分享链接时，用户需要先通过此接口验证密码。
     * 验证通过后前端通常会缓存验证状态，后续的文件列表请求需要携带正确的密码。
     * 密码验证失败会返回 false，前端应提示用户重新输入。</p>
     *
     * @param request 包含分享 key 和待验证密码的请求参数
     * @return 验证结果的 AjaxJson 响应，true 表示密码正确，false 表示密码错误
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "验证分享密码", description = "验证分享链接的访问密码")
    @PostMapping("/verify")
    public AjaxJson<Boolean> verifyPassword(@Valid @RequestBody VerifySharePasswordRequest request) {
        // 调用服务层验证分享密码是否正确
        boolean isValid = shareLinkService.verifyPassword(request.getShareKey(), request.getPassword());
        return AjaxJson.getSuccessData(isValid);
    }

    /**
     * 获取分享文件列表。
     *
     * <p>获取指定分享链接中的文件和文件夹列表。如果分享设置了密码保护，
     * 需要在请求中提供正确的密码。支持目录导航（通过 path 参数指定子目录）、
     * 排序（按名称、大小、时间等排序）等功能。</p>
     *
     * <p>该接口内部会通过 {@link ShareAccessContext} 设置分享访问上下文，
     * 确保文件操作被限制在分享的基础路径范围内，防止越权访问。</p>
     *
     * @param request 包含分享 key、目标路径、密码、排序方式等参数的请求体
     * @return 包含当前目录下的文件/文件夹列表以及面包屑导航信息的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 4)
    @Operation(summary = "获取分享文件列表", description = "获取分享链接中的文件和文件夹列表")
    @PostMapping("/files")
    public AjaxJson<ShareFileInfoResult> getShareFileList(@Valid @RequestBody ShareFileListRequest request) {
        // 处理请求参数默认值（如默认排序字段、默认排序方向等）
        request.handleDefaultValue();

        // 调用分享文件服务获取文件列表，传入分享key、路径、密码、排序等参数
        ShareFileInfoResult result = shareLinkFileService.getShareFileList(
                request.getShareKey(),
                request.getPath(),
                request.getPassword(),
                request.getFolderPassword(),
                request.getOrderBy(),
                request.getOrderDirection()
        );

        return AjaxJson.getSuccessData(result);
    }

    /**
     * 获取分享场景下的 OnlyOffice 在线预览配置令牌。
     *
     * <p>此接口用于在分享页面中支持 OnlyOffice 在线预览文档。
     * 与普通文件预览不同，分享场景有以下特殊处理：
     * <ul>
     *   <li>禁止编辑保存：通过设置 {@code canEdit = false} 确保分享访问者无法修改原文件</li>
     *   <li>使用分享者的用户ID作为缓存用户身份：即使回调请求被伪造，也只能关联到分享者，
     *       不会影响其他用户的数据</li>
     *   <li>通过 {@link ShareAccessContext} 传递分享上下文：确保 OnlyOffice 的
     *       下载/回调链接按照分享的路径视角进行解析</li>
     * </ul>
     * </p>
     *
     * <p><strong>安全说明：</strong>此接口通过分享密码验证和文件存在性校验
     * 双重保护，防止未授权访问。同时使用 try-finally 确保分享上下文被正确清理，
     * 避免 ThreadLocal 内存泄漏。</p>
     *
     * @param request 包含分享 key、文件路径、分享密码的请求参数
     * @return 包含 OnlyOffice 预览配置（包括 token、文档服务器地址等）的 AjaxJson 响应
     * @throws BizException 如果分享密码不正确或文件不存在
     */
    @ApiOperationSupport(order = 9)
    @Operation(summary = "获取分享 OnlyOffice 预览配置", description = "校验分享访问后生成 OnlyOffice 预览配置, 分享场景下不允许编辑保存")
    @PostMapping("/onlyOffice/config/token")
    public AjaxJson<JSONObject> getShareOnlyOfficeConfigToken(@Valid @RequestBody ShareOnlyOfficeConfigTokenRequest request) {
        // 处理请求参数默认值（如路径默认值等）
        request.handleDefaultValue();

        // 校验分享有效性 + 分享密码：获取有效的分享链接（检查是否存在、是否过期等）
        ShareLink shareLink = shareLinkService.getValidShareLink(request.getShareKey());
        // 验证分享密码，密码错误则直接抛出业务异常
        if (!shareLinkService.verifyPassword(request.getShareKey(), request.getSharePassword())) {
            throw new BizException("分享密码不正确");
        }

        // 通过分享文件服务获取实际文件信息(内部会设置 ShareAccessContext, 走分享 basePath).
        FileItemResult fileItem = shareLinkFileService.getShareFileItem(request.getShareKey(),
                request.getPath(),
                request.getSharePassword());
        // 如果文件不存在，抛出业务异常提示用户
        if (fileItem == null) {
            throw new BizException("文件不存在");
        }

        // 获取存储源 key 和文件的完整路径（分享基础路径 + 请求的相对路径）
        String storageKey = shareLink.getStorageKey();
        String fullPath = StringUtils.concat(shareLink.getSharePath(), request.getPath());

        // 构建 OnlyOffice 文件对象：
        // - 分享场景禁止编辑保存, 用 shareUserId 作为缓存用户身份, 即使 callback 伪造也只对应分享者.
        // - originalPath 透传 request.getPath(), 与普通预览保持一致, 避免回调写回时双重拼接.
        OnlyOfficeFile onlyOfficeFile = new OnlyOfficeFile(storageKey, fullPath, request.getPath(),
                shareLink.getUserId(), false);

        try {
            // 设置分享上下文, 让 createConfig 内部生成下载/回调链接时按分享视角解析.
            ShareAccessContext.setShareAccess(request.getShareKey(), shareLink.getSharePath(), shareLink.getUserId());
            // 调用 OnlyOffice 配置服务生成预览所需的配置信息（包含 token 等）
            JSONObject payload = onlyOfficeConfigService.createConfig(fileItem, onlyOfficeFile, false);
            return AjaxJson.getSuccessData(payload);
        } finally {
            // 无论成功或异常，都必须清理分享上下文，防止 ThreadLocal 内存泄漏
            ShareAccessContext.clear();
        }
    }

    /**
     * 下载分享文件。
     *
     * <p>通过分享链接下载指定的文件。此接口不直接返回文件流，而是：
     * <ol>
     *   <li>先通过 {@link ShareLinkFileService#getShareFileDownloadUrl} 获取实际的文件下载地址
     *       （直链地址，可能是对象存储的预签名 URL 等）</li>
     *   <li>返回 HTTP 302 重定向响应，浏览器会自动跳转到实际下载地址</li>
     * </ol>
     * 使用 302 重定向而非代理下载的好处是减少服务器带宽压力，大文件下载体验更好。
     * </p>
     *
     * <p>响应头中设置了禁用缓存的头信息（Cache-Control、Pragma、Expires），
     * 确保每次请求都能获取到有效的下载地址（预签名 URL 通常有时效性）。</p>
     *
     * @param shareKey 分享链接的唯一标识 key，从 URL 路径中获取
     * @param path     要下载的文件在分享中的相对路径，从 URL 查询参数中获取
     * @param password 分享密码（如果分享设置了密码保护），可选参数
     * @return HTTP 302 重定向响应，Location 头指向实际的文件下载地址；
     *         如果下载失败则返回 400 状态码并附带错误信息
     */
    @ApiOperationSupport(order = 5)
    @Operation(summary = "下载分享文件", description = "通过分享链接下载文件，返回302重定向到实际下载地址")
    @GetMapping("/download/{shareKey}")
    @Parameter(in = ParameterIn.PATH, name = "shareKey", description = "分享链接key", required = true, schema = @Schema(type = "string"))
    @Parameter(in = ParameterIn.QUERY, name = "path", description = "文件路径", required = true, schema = @Schema(type = "string"))
    @Parameter(in = ParameterIn.QUERY, name = "password", description = "分享密码", schema = @Schema(type = "string"))
    public ResponseEntity<?> downloadShareFile(
            @PathVariable String shareKey,
            @RequestParam String path,
            @RequestParam(required = false) String password) {
        try {
            // 获取实际下载地址（可能是对象存储的预签名 URL 等）
            String actualDownloadUrl = shareLinkFileService.getShareFileDownloadUrl(shareKey, path, password);

            // 302重定向到实际地址，设置禁用缓存的响应头以确保每次都获取最新的下载地址
            return ResponseEntity.status(302)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.LOCATION, actualDownloadUrl)
                    .build();

        } catch (Exception e) {
            // 记录下载失败的日志，包含 shareKey 和 path 方便排查问题
            log.error("分享文件下载失败, shareKey: {}, path: {}", shareKey, path, e);
            // 返回 400 状态码和错误信息给客户端
            return ResponseEntity.status(400).body(AjaxJson.getError(e.getMessage()));
        }
    }

    /**
     * 取消/删除分享链接。
     *
     * <p>删除指定的分享链接，删除后该分享将无法再被访问。
     * 此接口要求用户已登录（通过 {@link SaCheckLogin} 注解），
     * 且只能删除当前登录用户自己创建的分享链接。</p>
     *
     * @param shareKey 要删除的分享链接的唯一标识 key，从 URL 路径中获取
     * @return 删除成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 6)
    @Operation(summary = "取消分享", description = "删除指定的分享链接")
    @DeleteMapping("/{shareKey}")
    @Parameter(in = ParameterIn.PATH, name = "shareKey", description = "分享链接key", required = true, schema = @Schema(type = "string"))
    @SaCheckLogin
    public AjaxJson<Boolean> deleteShare(@PathVariable String shareKey) {
        // 调用服务层删除当前用户的指定分享链接
        shareLinkService.deleteShareLink(shareKey);
        return AjaxJson.getSuccessData(true);
    }

    /**
     * 获取当前用户的分享列表。
     *
     * <p>分页查询当前登录用户创建的所有分享链接。
     * 支持通过 {@link ShareLinkListRequest} 进行分页、排序和条件筛选。
     * 与管理员接口 {@link ShareFileManagerController#getShareList} 不同，
     * 此接口只会返回当前用户创建的分享记录，不会包含其他用户的分享。</p>
     *
     * @param request 分享列表查询请求参数，包含分页信息、排序方式、筛选条件等
     * @return 包含分页信息（总记录数）和分享记录列表的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 7)
    @Operation(summary = "获取用户分享列表", description = "获取当前用户创建的分享链接（支持分页与筛选）")
    @GetMapping("/list")
    public AjaxJson<List<ShareLinkResult>> getUserShareList(@Valid ShareLinkListRequest request) {
        // 调用服务层获取当前用户的分享分页数据
        Page<ShareLinkResult> result = shareLinkService.getUserShareList(request);
        // 将 MyBatis-Plus 分页结果封装为统一的 AjaxJson 分页响应格式
        return AjaxJson.getPageData(result.getTotal(), result.getRecords());
    }

    /**
     * 清理当前用户已过期的分享链接。
     *
     * <p>删除当前登录用户创建的所有已过期的分享记录（过期时间早于当前时间）。
     * 此接口要求用户已登录（通过 {@link SaCheckLogin} 注解），
     * 且只会清理当前用户的过期分享，不会影响其他用户。</p>
     *
     * <p>如果需要清理所有用户的过期分享（管理员操作），
     * 请使用 {@link ShareFileManagerController#deleteExpiredShares()} 方法。</p>
     *
     * @return 包含被删除的过期分享数量的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 8)
    @Operation(summary = "清理过期分享", description = "删除所有已过期的分享链接")
    @DeleteMapping("/expired")
    @SaCheckLogin
    public AjaxJson<Integer> deleteExpiredShares() {
        // 获取当前登录用户的 ID，确保只清理当前用户的过期分享
        Integer currentUserId = TlgxyCloudAuthUtil.getCurrentUserId();
        // 调用服务层删除当前用户的所有过期分享链接
        int deletedCount = shareLinkService.deleteExpiredLinksByUserId(currentUserId);
        return AjaxJson.getSuccessData(deletedCount);
    }
}
