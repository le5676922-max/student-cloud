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
 * 查询短链日志请求参数类
 * <p>
 * 继承 {@link PageQueryRequest}，在分页查询的基础上增加了短链查询特有的筛选条件。
 * 支持按短链 key、存储源 ID、文件路径和访问时间范围等条件筛选。
 * <p>
 * 提供了 {@link #getDateFrom()} 和 {@link #getDateTo()} 两个便捷方法，
 * 用于将前端传入的日期范围转换为当天的开始时间和结束时间。
 *
 * @author zhaojun
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryShortLinkLogRequest extends PageQueryRequest {

    /**
     * 短链 key 筛选条件
     */
    @Schema(title="短链 key")
    private String key;

    /**
     * 存储源 ID 筛选条件
     */
    @Schema(title="存储源 id")
    private String storageId;

    /**
     * 短链对应的文件路径筛选条件
     */
    @Schema(title="短链文件路径")
    private String url;

    /**
     * 访问时间范围，格式为 yyyy-MM-dd HH:mm:ss
     */
    @Schema(title="访问时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private List<Date> searchDate;

    /**
     * 获取查询的开始日期
     * <p>
     * 如果未设置日期范围则返回 null，否则返回日期列表中第一个日期的当天零点。
     *
     * @return 查询的开始日期（当天 00:00:00），或 null
     */
    public Date getDateFrom() {
        if (searchDate == null) {
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
        if (searchDate == null) {
            return null;
        }
        return DateUtil.endOfDay(searchDate.getLast());
    }
}