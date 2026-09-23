package im.tlgxy.cloud.module.sso.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.sso.model.entity.SsoConfig;
import im.tlgxy.cloud.module.sso.model.response.SsoLoginItemResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 单点登录（SSO）配置表的 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对 sso_config 表的基础 CRUD 操作。
 * 同时定义了一些自定义查询方法，用于查询所有配置、登录项列表、
 * 根据厂商名查找配置以及检查厂商名是否重复。
 * </p>
 *
 * @author OnEvent
 */
@Mapper
public interface SsoConfigMapper extends BaseMapper<SsoConfig> {

    /**
     * 查询所有单点登录配置
     * <p>
     * 返回数据库中所有 SSO 配置记录，包括已启用和未启用的配置。
     * </p>
     *
     * @return 所有 SSO 配置的列表
     */
    List<SsoConfig> findAll();

    /**
     * 查询所有已启用的单点登录配置（仅包含登录页展示所需字段）
     * <p>
     * 仅返回 provider、name、icon 三个字段，用于登录页展示 SSO 登录入口。
     * 通常只返回 enabled = true 的配置。
     * </p>
     *
     * @return 登录页展示用的 SSO 登录项列表
     */
    List<SsoLoginItemResponse> findAllLoginItems();

    /**
     * 根据厂商名（provider）查找单点登录配置
     * <p>
     * 用于根据唯一的厂商标识符获取对应的完整 SSO 配置信息，
     * 包括 client_id、client_secret、各个端点 URL 等敏感信息。
     * </p>
     *
     * @param provider OIDC/OAuth2 厂商名（唯一标识）
     * @return 对应的 SSO 配置实体，未找到时返回 null
     */
    SsoConfig findByProvider(@Param("provider") String provider);

    /**
     * 统计指定厂商名的配置数量（排除指定 ID 的记录）
     * <p>
     * 用于检查厂商名是否重复。在新增或更新配置时，
     * 通过排除自身 ID（ignoreId）来判断其他记录中是否已存在相同的 provider。
     * </p>
     *
     * @param provider 要检查的厂商名
     * @param ignoreId 需要忽略的记录 ID（通常为当前正在编辑的记录 ID，新增时为 null）
     * @return 匹配的记录数量，大于 0 表示存在重复
     */
    int countByProvider(@Param("provider") String provider, @Param("ignoreId") Integer ignoreId);

}
