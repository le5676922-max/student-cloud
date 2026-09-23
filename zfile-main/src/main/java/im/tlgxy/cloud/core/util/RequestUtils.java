package im.tlgxy.cloud.core.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.util.CollectionUtils;

/**
 * HTTP 请求工具类
 * <p>
 * 提供与 HTTP 请求相关的便捷工具方法，如解析 Range 请求头等。
 * </p>
 *
 * @author zhaojun
 */
public class RequestUtils {

    /**
     * 从请求中获取 HTTP Range 信息
     * <p>
     * 解析请求头中的 <code>Range</code> 字段，返回第一个合法的 {@link HttpRange} 对象。
     * Range 请求头用于 HTTP 断点续传场景，客户端通过该头指定需要下载的字节范围。
     * 如果请求中没有 Range 头，则返回 null。
     * </p>
     *
     * @param   request
     *          HTTP 请求对象，不能为 null
     *
     * @return  第一个合法的 HttpRange 对象；如果请求头中没有 Range 字段或解析结果为空，则返回 null
     */
    public static HttpRange getRequestRange(HttpServletRequest request) {
        // 从请求头中获取 Range 字段的值
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        // 如果没有 Range 请求头，直接返回 null
        if (rangeHeader == null) {
            return null;
        }
        // 解析 Range 字符串并返回第一个有效的 HttpRange 对象
        return CollectionUtils.firstElement(HttpRange.parseRanges(rangeHeader));
    }

}