package im.tlgxy.cloud.module.log.model.entity;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.core.util.RequestHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;

import java.io.Serializable;
import java.util.Date;

/**
 * 文件下载日志实体类
 * <p>
 * 对应数据库表 download_log，记录每一次文件下载的详细信息，
 * 包括下载类型（直链/短链）、文件路径、存储源、访问者 IP、User-Agent、Referer 等。
 * 提供便捷构造器，可从当前 HTTP 请求中自动填充 IP、Referer、User-Agent 等信息。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Tag(name ="文件下载日志")
@TableName(value = "`download_log`")
@NoArgsConstructor
public class DownloadLog implements Serializable {

    /** 下载类型常量：直链下载 */
    public static final String DOWNLOAD_TYPE_DIRECT_LINK = "directLink";

    /** 下载类型常量：短链下载 */
    public static final String DOWNLOAD_TYPE_SHORT_LINK = "shortLink";

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /** 下载类型：directLink（直链）或 shortLink（短链） */
    @TableField(value = "`download_type`")
    @Schema(title="下载类型", example = "directLink", allowableValues = "directLink, shortLink")
    private String downloadType;

    /** 被下载文件的路径 */
    @TableField(value = "`path`")
    @Schema(title="文件路径")
    private String path;

    /** 存储源 key，标识文件所属的存储源 */
    @TableField(value = "`storage_key`")
    @Schema(title="存储源 key")
    private String storageKey;

    /** 下载发生的时间 */
    @TableField(value = "`create_time`")
    @Schema(title="访问时间")
    private Date createTime;

    /** 访问者的 IP 地址 */
    @TableField(value = "`ip`")
    @Schema(title="访问 ip")
    private String ip;

    /** 短链 key（仅在通过短链下载时有值） */
    @TableField(value = "short_key")
    @Schema(title = "短链 key", example = "voldd3")
    private String shortKey;

    /** 访问者的 User-Agent 信息 */
    @TableField(value = "`user_agent`")
    @Schema(title="访问 user_agent")
    private String userAgent;

    /** 访问来源页面的 Referer 信息 */
    @TableField(value = "`referer`")
    @Schema(title="访问 referer")
    private String referer;

    /**
     * 带参数的构造器，用于创建一条下载日志记录
     * <p>
     * 自动从当前 HTTP 请求中获取 IP、Referer 和 User-Agent 信息，
     * 并设置创建时间为当前时间。
     * </p>
     *
     * @param downloadType 下载类型（直链或短链）
     * @param path         下载文件路径
     * @param storageKey   存储源 key
     * @param shortKey     短链 key（直链下载时可为 null）
     */
    public DownloadLog(String downloadType, String path, String storageKey, String shortKey) {
        this.downloadType = downloadType;
        this.path = path;
        this.storageKey = storageKey;
        this.shortKey = shortKey;
        this.createTime = new Date();
        // 从当前请求上下文中获取 HTTP 请求对象
        HttpServletRequest request = RequestHolder.getRequest();
        // 自动获取客户端真实 IP（支持代理转发场景）
        this.ip = JakartaServletUtil.getClientIP(request);
        // 获取 Referer 头
        this.referer = request.getHeader(HttpHeaders.REFERER);
        // 获取 User-Agent 头
        this.userAgent = request.getHeader(HttpHeaders.USER_AGENT);
    }

}