package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.session.SaSession;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jackson 定制版 SaSession。
 * <p>
 * 继承自 Sa-Token 的 {@link SaSession}，通过 Jackson 的 {@link JsonIgnoreProperties} 注解
 * 在序列化到 Redis（使用 Jackson 作为序列化工具）时忽略 <b>timeout</b> 属性，
 * 避免超时字段与 Redis 本身的过期机制产生冲突。
 * </p>
 * <p>
 * 此定制版 Session 在 {@link SaTokenDaoRedisJackson} 初始化时，
 * 通过重写 SaStrategy 的 Session 创建策略来使用。
 * </p>
 *
 * @author click33
 * @since 1.34.0
 */
// 在 Jackson 序列化时忽略 timeout 属性，因为 Redis 本身管理 key 的过期时间
@JsonIgnoreProperties({"timeout"})
public class SaSessionForJacksonCustomized extends SaSession {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = -7600983549653130681L;

    /**
     * 无参构造方法，创建一个新的 Session 对象。
     */
    public SaSessionForJacksonCustomized() {
        super();
    }

    /**
     * 带参构造方法，构建一个指定 ID 的 Session 对象。
     *
     * @param id Session 的唯一标识 ID
     */
    public SaSessionForJacksonCustomized(String id) {
        super(id);
    }

}
