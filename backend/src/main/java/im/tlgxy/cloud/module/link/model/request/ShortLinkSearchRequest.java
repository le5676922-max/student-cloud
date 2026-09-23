package im.tlgxy.cloud.module.link.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 短链接搜索请求参数类
 * <p>
 * 封装短链搜索功能所需的所有请求参数，包括筛选条件、分页参数和排序参数。
 * 支持按存储源 ID、短链 key、文件路径、创建时间范围等条件进行组合查询。
 * <p>
 * 分页参数（page、limit）和排序参数（orderBy、orderDirection）支持前端自定义，
 * 方便在管理后台的短链列表中进行数据浏览。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "搜索存储源中文件请求类")
public class ShortLinkSearchRequest {

    /**
     * 存储源 ID，必填字段，用于筛选指定存储源的短链
     */
    @Schema(title = "存储源 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotBlank(message = "存储源 id 不能为空")
    private Integer storageId;

    /**
     * 短链 key 筛选条件，支持模糊匹配
     */
    @Schema(title = "存储源 key", example = "local")
    private String key;

    /**
     * 文件路径筛选条件，支持模糊匹配
     */
    @Schema(title = "文件 url/路径", example = "/a")
    private String url;

    /**
     * 创建时间范围的开始时间
     */
    @Schema(title = "开始时间", example = "2022-01-01 00:00:00")
    private String dateFrom;

    /**
     * 创建时间范围的结束时间
     */
    @Schema(title = "结束时间", example = "2022-12-31 23:59:59")
    private String dateTo;

    /**
     * 当前页码，用于分页查询
     */
    @Schema(title = "页码", example = "1")
    private Integer page;

    /**
     * 每页记录数，用于分页查询
     */
    @Schema(title = "每页数量", example = "10")
    private Integer limit;

    /**
     * 排序字段名
     */
    @Schema(title = "排序字段", example = "id")
    private String orderBy;

    /**
     * 排序方向，asc 表示升序，desc 表示降序
     */
    @Schema(title = "排序方式", example = "desc")
    private String orderDirection;

}