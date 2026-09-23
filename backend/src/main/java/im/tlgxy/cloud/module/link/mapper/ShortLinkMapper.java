package im.tlgxy.cloud.module.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import jakarta.annotation.Nullable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 短链接 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 {@link BaseMapper}，自动获得基础的 CRUD 操作方法。
 * 同时定义了一些自定义的数据库查询和更新方法，通过对应的 XML 映射文件实现具体的 SQL 逻辑。
 * <p>
 * 提供的方法包括：
 * <ul>
 *   <li>根据短链 key 精确查找短链</li>
 *   <li>根据存储源 ID 和 URL 查询短链（用于判断是否已存在相同链接）</li>
 *   <li>根据存储源 ID 删除所有关联的短链</li>
 *   <li>批量删除已过期的短链</li>
 * </ul>
 *
 * @author zhaojun
 */
@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {

    /**
     * 根据短链接 key 查询短链接
     * <p>
     * 通过唯一的短链 key 查找对应的短链记录，用于短链跳转时获取原始文件路径。
     *
     * @param   key
     *          短链接 key，由系统随机生成的 6 位字符串
     *
     * @return  短链接信息实体，如果未找到则返回 null
     */
    ShortLink findByKey(@Param("key")String key);


    /**
     * 根据存储源 ID 删除所有关联的短链数据
     * <p>
     * 当删除某个存储源时，需要同步删除该存储源下的所有短链记录。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  被删除的记录数
     */
    int deleteByStorageId(@Param("storageId") Integer storageId);

    /**
     * 根据存储源 ID、文件 URL 和过期时间查询短链接
     * <p>
     * 用于检查指定存储源和文件路径是否已经存在短链，
     * 如果存在则直接返回，避免重复生成。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   url
     *          文件路径
     *
     * @param   expireDate
     *          过期时间，可为 null
     *
     * @return  匹配的短链接信息，如果未找到则返回 null
     */
    ShortLink findByStorageIdAndUrl(@Param("storageId") Integer storageId,
                                    @Param("url") String url,
                                    @Nullable @Param("expireDate") Date expireDate);

    /**
     * 删除所有已过期的短链接
     * <p>
     * 清理数据库中过期时间早于当前时间的所有短链记录。
     *
     * @return 被删除的行数
     */
    int deleteExpireLink();

}