package im.tlgxy.cloud.module.share.model.request;

import cn.hutool.core.util.StrUtil;
import im.tlgxy.cloud.core.model.request.PageQueryRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * 用户分享列表分页请求对象
 * <p>
 * 继承自 {@link PageQueryRequest}，在分页参数的基础上增加了分享模块特有的筛选条件。
 * 支持按关键词模糊搜索、按分享状态过滤、按存储源过滤以及按创建时间范围查询。
 * 通过 {@link #handleDefaultValue()} 方法对分页参数和排序参数进行默认值填充与边界校验。
 * </p>
 *
 * @author zhaojun
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用户分享列表分页请求")
public class ShareLinkListRequest extends PageQueryRequest {

    /**
     * 搜索关键词，支持对分享 key 和分享名称进行模糊匹配
     */
    @Schema(title = "搜索关键词")
    private String keyword;

    /**
     * 分享状态过滤条件
     * <ul>
     *   <li>"all" - 全部分享（默认值）</li>
     *   <li>"active" - 仅查询有效分享（未过期）</li>
     *   <li>"expired" - 仅查询已过期分享</li>
     * </ul>
     */
    @Schema(title = "分享状态", allowableValues = {"all", "active", "expired"})
    private String status;

    /**
     * 存储源 key 过滤条件，传入时仅返回属于该存储源的分享记录
     */
    @Schema(title = "存储源 key")
    private String storageKey;

    /**
     * 创建时间范围起始，用于筛选在此时间之后创建的分享
     */
    @Schema(title = "创建时间起始", example = "2024-01-01 00:00:00")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createDateStart;

    /**
     * 创建时间范围结束，用于筛选在此时间之前创建的分享
     */
    @Schema(title = "创建时间结束", example = "2024-01-31 23:59:59")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createDateEnd;

    /**
     * 处理请求参数的默认值并进行边界校验
     * <p>
     * 对各参数进行如下处理：
     * <ul>
     *   <li>页码（page）：未传或小于 1 时默认为第 1 页</li>
     *   <li>每页条数（limit）：未传或小于 1 时默认为 10 条，超过 100 条时强制限制为 100 条</li>
     *   <li>排序字段（orderBy）：未传时默认按创建时间（create_date）排序</li>
     *   <li>排序方向（orderDirection）：未传时默认降序（desc），即最新创建的在前</li>
     *   <li>分享状态（status）：未传时默认查询所有状态（all）</li>
     * </ul>
     * </p>
     */
    public void handleDefaultValue() {
        // 页码校验：未传或小于 1 时默认为第 1 页
        if (getPage() == null || getPage() < 1) {
            setPage(1);
        }
        // 每页条数校验：未传或小于 1 时默认为 10 条
        if (getLimit() == null || getLimit() < 1) {
            setLimit(10);
        }
        // 每页条数上限校验：超过 100 条时强制限制，防止一次查询过多数据
        if (getLimit() > 100) {
            setLimit(100);
        }
        // 默认排序字段：按创建时间排序
        if (StrUtil.isBlank(getOrderBy())) {
            setOrderBy("create_date");
        }
        // 默认排序方向：降序，最新创建的分享排在前面
        if (StrUtil.isBlank(getOrderDirection())) {
            setOrderDirection("desc");
        }
        // 默认分享状态：查询所有状态
        if (StrUtil.isBlank(status)) {
            status = "all";
        }
    }
}
