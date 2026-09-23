package im.tlgxy.cloud.core.util;

import im.tlgxy.cloud.core.constant.TlgxyCloudConstant;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.GetPreviewTextContentBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * HTTP 网络请求工具类
 * <p>
 * 提供基于 HTTP 协议的网络操作，包括：
 * <ul>
 *     <li>获取远程 URL 对应的文本内容</li>
 *     <li>获取远程文件的大小（通过 HTTP Content-Length 头）</li>
 * </ul>
 * <p>
 * 该类封装了 Hutool 的 HttpUtil，并增加了文件大小限制等业务校验逻辑，
 * 确保不会因下载过大的文件而导致内存溢出。
 *
 * @author zhaojun
 */
@Slf4j
public class HttpUtil {

    /**
     * 获取指定 URL 对应的文本内容
     * <p>
     * 在获取内容之前会先检查远程文件大小，如果超过系统允许的最大限制
     *（{@link TlgxyCloudConstant#TEXT_MAX_FILE_SIZE_KB} * 1024 字节），
     * 则抛出 {@link BizException} 异常，防止大文件导致内存问题。
     * <p>
     * 该方法主要用于预览文本类文件（如代码、日志、配置文件等）。
     *
     * @param url 文件的远程 URL 地址，不能为空
     * @return 文件文本内容；如果远程返回 null 则返回空字符串 ""
     * @throws BizException 当远程文件大小超过限制时抛出
     * @throws GetPreviewTextContentBizException 当 HTTP 请求发生异常时抛出
     */
    public static String getTextContent(String url) {
        // 计算最大允许的文件大小（单位：字节）
        long maxFileSize = 1024 * TlgxyCloudConstant.TEXT_MAX_FILE_SIZE_KB;

        // 先检查远程文件大小，超过限制则直接拒绝，避免大文件 OOM
        if (getRemoteFileSize(url) > maxFileSize) {
            throw new BizException(ErrorCode.BIZ_PREVIEW_FILE_SIZE_EXCEED);
        }

        String result;
        try {
            // 使用 Hutool 工具类发起 HTTP GET 请求获取文本内容
            result = cn.hutool.http.HttpUtil.get(url);
        } catch (Exception e) {
            throw new GetPreviewTextContentBizException(url, e);
        }

        // 返回结果，如果为 null 则返回空字符串
        return result == null ? "" : result;
    }


    /**
     * 获取远程文件的大小（字节数）
     * <p>
     * 通过建立 HTTP 连接并读取响应头中的 {@code Content-Length} 字段来获取文件大小。
     * 该方法仅获取元数据，不会下载文件内容，因此开销很小。
     *
     * @param url 文件的远程 URL 地址，不能为空
     * @return 文件大小（字节）；如果获取失败则返回 0
     */
    public static Long getRemoteFileSize(String url) {
        long size = 0;
        URL urlObject;
        try {
            // 构建 URL 对象并打开连接
            urlObject = new URL(url);
            URLConnection conn = urlObject.openConnection();
            // 从响应头 Content-Length 获取文件大小
            size = conn.getContentLength();
        } catch (IOException e) {
            // 获取失败时打印堆栈并返回默认值 0
            e.printStackTrace();
        }

        return size;
    }

}