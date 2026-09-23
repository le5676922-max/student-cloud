package im.tlgxy.cloud.module.share.context;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 分享访问上下文，用于在分享访问时传递相关信息。
 *
 * <p>核心作用：通过 {@link ThreadLocal} 在当前请求线程中传递分享访问的上下文信息，
 * 解决分享访问时绕过用户基础路径限制的问题。当用户通过分享链接访问文件时，
 * 需要在请求处理链路中标识当前访问为"分享访问"，并提供分享的基础路径、
 * 分享 key 等信息，以便后续的文件操作（如列表、下载、预览）能够在分享的
 * 路径范围内进行，而不会越权访问到用户存储源的其他路径。</p>
 *
 * <p>使用方式：
 * <ul>
 *   <li>在请求进入分享相关接口时，调用 {@link #setShareAccess(String, String)} 或
 *       {@link #setShareAccess(String, String, Integer)} 设置上下文</li>
 *   <li>在后续的业务逻辑中，通过 {@link #isShareAccess()} 判断当前是否为分享访问</li>
 *   <li>通过 {@link #getShareBasePath()} 等方法获取具体的分享信息</li>
 *   <li>请求处理完毕后，<strong>必须</strong>调用 {@link #clear()} 清理上下文，
 *       防止 {@link ThreadLocal} 内存泄漏（尤其是在使用线程池的场景下）</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Component
public class ShareAccessContext {

    /**
     * 用于存储当前线程分享访问上下文的 ThreadLocal 变量。
     * 使用 ThreadLocal 确保每个请求线程拥有独立的分享访问信息，线程间互不干扰。
     * 注意：由于使用了 ThreadLocal，在请求结束或发生异常时必须调用 clear() 清理，
     * 否则在线程池复用的场景下会造成数据串扰或内存泄漏。
     */
    private static final ThreadLocal<ShareAccessInfo> CONTEXT = new ThreadLocal<>();

    /**
     * 分享访问信息内部类。
     * 封装了单次分享访问所需的全部上下文数据，包括：
     * <ul>
     *   <li>是否为分享访问的标识</li>
     *   <li>分享的基础路径（用于限制文件操作范围）</li>
     *   <li>分享链接的唯一标识 key</li>
     *   <li>分享者的用户 ID（用于权限校验和审计）</li>
     * </ul>
     */
    @Getter
    @AllArgsConstructor
    public static class ShareAccessInfo {
        /** 是否为分享访问 */
        private boolean isShareAccess;
        /** 分享的基础路径，所有分享访问的文件操作都将限定在此路径范围内 */
        private String shareBasePath;
        /** 分享链接的唯一标识 key */
        private String shareKey;
        /** 分享者的用户 ID，用于标识分享的创建者 */
        private Integer shareUserId;
    }

    /**
     * 设置分享访问上下文（不带分享者用户ID）。
     * 这是一个便捷方法，内部调用三参数版本，将 shareUserId 置为 null。
     *
     * @param shareKey      分享链接 key，用于标识具体的分享
     * @param shareBasePath 分享的基础路径，后续文件操作将在此路径范围内进行
     */
    public static void setShareAccess(String shareKey, String shareBasePath) {
        setShareAccess(shareKey, shareBasePath, null);
    }

    /**
     * 设置分享访问上下文（带分享者用户ID）。
     * 将分享访问信息封装为 {@link ShareAccessInfo} 对象并存入当前线程的 ThreadLocal 中。
     *
     * @param shareKey      分享链接 key，用于标识具体的分享
     * @param shareBasePath 分享的基础路径，后续文件操作将在此路径范围内进行
     * @param shareUserId   分享者用户ID，用于在 OnlyOffice 预览等场景下关联分享者身份
     */
    public static void setShareAccess(String shareKey, String shareBasePath, Integer shareUserId) {
        CONTEXT.set(new ShareAccessInfo(true, shareBasePath, shareKey, shareUserId));
    }

    /**
     * 检查当前线程是否为分享访问。
     * 通过判断 ThreadLocal 中的 {@link ShareAccessInfo} 是否存在且 isShareAccess 为 true 来确定。
     *
     * @return 如果是分享访问返回 true，否则返回 false
     */
    public static boolean isShareAccess() {
        ShareAccessInfo info = CONTEXT.get();
        return info != null && info.isShareAccess;
    }

    /**
     * 获取当前分享的基础路径。
     * 该路径用于在后续文件列表、下载等操作中限制可访问的文件范围，
     * 防止用户通过修改请求参数越权访问到存储源的其他路径。
     *
     * @return 分享基础路径，如果当前不是分享访问（即 ThreadLocal 中无数据）则返回 null
     */
    public static String getShareBasePath() {
        ShareAccessInfo info = CONTEXT.get();
        return info != null ? info.shareBasePath : null;
    }

    /**
     * 获取当前分享链接的唯一标识 key。
     * 可用于日志记录、审计追踪等场景，确定当前请求对应的是哪个分享链接。
     *
     * @return 分享链接 key，如果当前不是分享访问（即 ThreadLocal 中无数据）则返回 null
     */
    public static String getShareKey() {
        ShareAccessInfo info = CONTEXT.get();
        return info != null ? info.shareKey : null;
    }

    /**
     * 获取当前分享者的用户ID。
     * 在 OnlyOffice 预览等需要关联用户身份的场景中使用，
     * 确保回调操作的执行者身份与分享者一致，防止权限伪造。
     *
     * @return 分享者用户ID，如果当前不是分享访问（即 ThreadLocal 中无数据）则返回 null
     */
    public static Integer getShareUserId() {
        ShareAccessInfo info = CONTEXT.get();
        return info != null ? info.shareUserId : null;
    }

    /**
     * 清理当前线程的分享访问上下文。
     *
     * <p><strong>重要：</strong>此方法必须在分享访问请求处理结束后调用，
     * 否则会造成以下问题：
     * <ol>
     *   <li>在线程池复用场景下，下一个使用该线程的请求可能获取到上一个请求的分享上下文，
     *       导致数据串扰和安全风险</li>
     *   <li>ThreadLocal 中的 {@link ShareAccessInfo} 对象无法被 GC 回收，造成内存泄漏</li>
     * </ol>
     * 通常建议在 finally 块中调用此方法，以确保即使发生异常也能正确清理。
     * </p>
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 获取当前线程的完整分享访问信息。
     * 与单独的 getter 方法不同，此方法返回整个 {@link ShareAccessInfo} 对象，
     * 方便在需要一次性获取所有分享上下文信息的场景中使用。
     *
     * @return 当前的分享访问信息对象，如果当前不是分享访问则返回 null
     */
    public static ShareAccessInfo getCurrentInfo() {
        return CONTEXT.get();
    }
}
