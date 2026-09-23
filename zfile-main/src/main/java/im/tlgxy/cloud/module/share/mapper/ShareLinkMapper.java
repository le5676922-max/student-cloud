package im.tlgxy.cloud.module.share.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.share.model.entity.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 分享链接数据访问层（Mapper 接口）
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基本的 CRUD 操作能力。
 * 同时定义了自定义的 SQL 查询方法，对应的 SQL 语句通常在 XML 映射文件中实现。
 * 主要功能包括：
 * <ul>
 *   <li>根据分享 key 查询分享链接</li>
 *   <li>根据用户 ID 查询分享链接列表</li>
 *   <li>递增访问次数和下载次数</li>
 *   <li>清理过期的分享链接</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface ShareLinkMapper extends BaseMapper<ShareLink> {

    /**
     * 根据分享 key 获取分享链接信息
     * <p>
     * 通过唯一的分享 key（短标识符）查询对应的分享链接记录。
     * 分享 key 通常是一串随机生成的字符串，用于构建分享 URL。
     * </p>
     *
     * @param shareKey 分享链接的唯一标识 key，用于在 URL 中定位分享资源
     * @return 匹配的 ShareLink 实体对象，如果未找到则返回 null
     */
    ShareLink getByShareKey(@Param("shareKey") String shareKey);

    /**
     * 根据用户 ID 获取该用户创建的所有分享链接列表
     * <p>
     * 用于查询某个用户创建的全部分享记录，通常在"我的分享"页面中使用。
     * </p>
     *
     * @param userId 创建分享链接的用户 ID
     * @return 该用户创建的所有分享链接列表，如果没有则返回空列表
     */
    List<ShareLink> getByUserId(@Param("userId") Integer userId);

    /**
     * 递增指定分享链接的访问次数
     * <p>
     * 每次有用户访问分享链接时调用，用于统计分享的访问热度。
     * 采用原子递增的方式，直接在数据库层面增加计数值，
     * 避免并发场景下的计数不准确问题。
     * </p>
     *
     * @param shareKey  分享链接的唯一标识 key
     * @param increment 需要增加的访问次数（通常为 1）
     * @return 受影响的行数，通常为 1 表示更新成功
     */
    int incrementAccessCount(@Param("shareKey") String shareKey, @Param("increment") int increment);

    /**
     * 递增指定分享链接的下载次数
     * <p>
     * 每次有用户通过分享链接下载文件时调用，用于统计分享的下载量。
     * 与访问次数分开统计，便于更精细的数据分析。
     * </p>
     *
     * @param shareKey  分享链接的唯一标识 key
     * @param increment 需要增加的下载次数（通常为 1）
     * @return 受影响的行数，通常为 1 表示更新成功
     */
    int incrementDownloadCount(@Param("shareKey") String shareKey, @Param("increment") int increment);

    /**
     * 批量删除所有已过期的分享链接
     * <p>
     * 根据当前时间判断，删除所有过期时间早于当前时间的分享链接。
     * 通常由定时任务调用，用于定期清理无效的分享数据。
     * </p>
     *
     * @param currentTime 当前时间，用于与过期时间字段进行比较
     * @return 被删除的记录数
     */
    int deleteExpiredLinks(@Param("currentTime") Date currentTime);

    /**
     * 删除指定用户的已过期分享链接
     * <p>
     * 与 deleteExpiredLinks 类似，但只针对指定用户 ID 的记录进行清理。
     * 可以在用户登录或查看个人分享列表时触发，保持用户数据的整洁。
     * </p>
     *
     * @param userId      用户 ID，用于限定删除范围
     * @param currentTime 当前时间，用于与过期时间字段进行比较
     * @return 被删除的记录数
     */
    int deleteExpiredLinksByUserId(@Param("userId") Integer userId, @Param("currentTime") Date currentTime);

}
