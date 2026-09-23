package im.tlgxy.cloud.module.link.model.request;

import cn.hutool.core.date.DateUtil;
import im.tlgxy.cloud.core.model.request.PageQueryRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;
import java.util.List;

/**
 * 查询下载日志请求参数类
 * <p>
 * 继承 {@link PageQueryRequest}，在分页查询的基础上增加了下载日志特有的筛选条件。
 * 支持按文件路径、存储源 key、链接类型、短链 key、访问时间范围、IP、User-Agent、Referer 等条件筛选。
 * <p>
 * 提供了 {@link #getDateFrom()} 和 {@link #getDateTo()} 两个便捷方法，
 * 用于将前端传入的日期范围转换为当天的开始时间和结束时间。
 *
 * @author zhaojun
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryDownloadLogRequest extends PageQueryRequest {

    /**
     * 文件路径筛选条件，支持模糊匹配
     */
    @Schema(title="文件路径")
    private String path;

    /**
     * 存储源 key 筛选条件
     */
    @Schema(title="存储源 key")
    private String storageKey;

    /**
     * 链接类型筛选条件（如 directLink 直链、shortLink 短链）
     */
    @Schema(title="链接类型")
    private String linkType;

    /**
     * 短链 key 筛选条件
     */
    @Schema(title="短链 key")
    private String shortKey;

    /**
     * 访问时间范围，格式为 yyyy-MM-dd HH:mm:ss
     */
    @Schema(title="访问时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private List<Date> searchDate;

    /**
     * 访问者 IP 筛选条件
     */
    @Schema(title="访问 ip")
    private String ip;

    /**
     * 访问者 User-Agent 筛选条件
     */
    @Schema(title="访问 user_agent")
    private String userAgent;

    /**
     * 访问者 Referer 筛选条件
     */
    @Schema(title="访问 referer")
    private String referer;

    /**
     * 排序字段，默认按创建时间排序
     */
    @Schema(title="排序字段")
    private String orderBy = "create_time";

    /**
     * 获取查询的开始日期
     * <p>
     * 如果未设置日期范围则返回 null，否则返回日期列表中第一个日期的当天零点。
     *
     * @return 查询的开始日期（当天 00:00:00），或 null
     */
    public Date getDateFrom() {
        if (searchDate == null || searchDate.isEmpty()) {
            return null;
        }
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
        if (searchDate == null || searchDate.isEmpty()) {
            return null;
        }
        return DateUtil.endOfDay(searchDate.getLast());
    }

}