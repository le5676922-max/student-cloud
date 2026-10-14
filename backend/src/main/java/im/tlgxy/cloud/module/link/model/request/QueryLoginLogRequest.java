package im.tlgxy.cloud.module.link.model.request;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.model.request.PageQueryRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;
import java.util.List;

/**
 * 查询登录日志请求参数类
 * <p>
 * 继承 {@link PageQueryRequest}，在分页查询的基础上增加了登录日志特有的筛选条件。
 * 支持按用户名、IP、User-Agent、Referer、登录结果和访问时间范围等条件筛选。
 * <p>
 * 提供了 {@link #getDateFrom()} 和 {@link #getDateTo()} 两个便捷方法，
 * 用于将前端传入的日期范围转换为当天的开始时间和结束时间。
 *
 * @author zhaojun
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryLoginLogRequest extends PageQueryRequest {

    /**
     * 用户名筛选条件
     */
    @Schema(title="用户名")
    private String username;

    /**
     * 历史客户端兼容字段；密码不会用于筛选，也不会返回给前端。
     */
    @Schema(hidden = true)
    private String password;

    /**
     * 登录 IP 筛选条件
     */
    @Schema(title="IP")
    private String ip;

    /**
     * 登录 User-Agent 筛选条件
     */
    @Schema(title="User-Agent")
    private String userAgent;

    /**
     * 来源（Referer）筛选条件
     */
    @Schema(title="来源")
    private String referer;

    /**
     * 登录结果筛选条件（如成功/失败）
     */
    @Schema(title="登录结果")
    private String result;

    /**
     * 访问时间范围，格式为 yyyy-MM-dd HH:mm:ss
     */
    @Schema(title="访问时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private List<Date> searchDate;

    /**
     * 排序字段，默认按创建时间排序
     */
    @Schema(title="排序字段")
    private String orderBy = "create_time";

    /** Bound expensive administration queries and reject caller-provided SQL identifiers. */
    public void validateForQuery() {
        if (getPage() == null || getPage() < 1 || getPage() > 10_000
                || getLimit() == null || getLimit() < 1 || getLimit() > 100) {
            throw new BizException("页码须为1至10000，每页条数须为1至100");
        }
        validatedSortColumn();
        if (!"asc".equals(getOrderDirection()) && !"desc".equals(getOrderDirection())) {
            throw new BizException("排序方向只允许asc或desc");
        }
        validateDateRange();
    }

    @Override
    public OrderItem getOrderItem() {
        validateForQuery();
        String column = validatedSortColumn();
        return "asc".equals(getOrderDirection()) ? OrderItem.asc(column) : OrderItem.desc(column);
    }

    private String validatedSortColumn() {
        if (orderBy == null) throw new BizException("排序字段不正确");
        return switch (orderBy) {
            case "id" -> "id";
            case "username" -> "username";
            case "create_time", "createTime" -> "create_time";
            case "ip" -> "ip";
            case "result" -> "result";
            default -> throw new BizException("排序字段不正确");
        };
    }

    private boolean hasCompleteDateRange() {
        return searchDate != null && searchDate.size() == 2
                && searchDate.getFirst() != null && searchDate.getLast() != null;
    }

    private void validateDateRange() {
        if (searchDate != null && searchDate.size() > 2) throw new BizException("请选择一个完整的登录时间范围");
        if (hasCompleteDateRange() && searchDate.getFirst().after(searchDate.getLast())) {
            throw new BizException("登录时间范围的开始时间不能晚于结束时间");
        }
    }

    /**
     * 获取查询的开始日期
     * <p>
     * 如果未设置日期范围则返回 null，否则返回日期列表中第一个日期的当天零点。
     *
     * @return 查询的开始日期（当天 00:00:00），或 null
     */
    public Date getDateFrom() {
        validateDateRange();
        if (!hasCompleteDateRange()) return null;
        return DateUtil.beginOfDay(searchDate.getFirst());
    }

    /**
     * 获取查询的结束日期
     * <p>
     * 如果未设置日期范围则返回 null，否则返回日期列表中最后一个日期的当天最后一刻。
     *
     * @return 查询的结束日期（当天 23:59:59），或 null
     */
    public Date getDateTo() {
        validateDateRange();
        if (!hasCompleteDateRange()) return null;
        return DateUtil.endOfDay(searchDate.getLast());
    }

}
