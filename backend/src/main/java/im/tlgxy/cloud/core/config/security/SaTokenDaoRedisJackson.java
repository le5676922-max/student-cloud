//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.dev33.satoken.util.SaFoxUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sa-Token 持久层实现 —— 基于 Redis 存储、使用 Jackson 进行 JSON 序列化。
 * <p>
 * 实现了 {@link SaTokenDao} 接口，将 Sa-Token 的用户登录会话（Session）、Token 等数据
 * 持久化到 Redis 中，而不是默认的内存存储。这样在分布式部署或多实例环境下，
 * 多个服务实例可以共享同一份会话数据，实现单点登录和会话一致性。
 * </p>
 * <p>
 * 核心设计：
 * </p>
 * <ul>
 *   <li><b>StringRedisTemplate：</b>用于字符串类型的键值对读写（如 Token 的值本身）。</li>
 *   <li><b>RedisTemplate&lt;String, Object&gt;：</b>用于对象类型的键值对读写（如 Session 对象），
 *       使用 GenericJackson2JsonRedisSerializer 作为值序列化器。</li>
 *   <li><b>ObjectMapper 定制：</b>通过反射获取 GenericJackson2JsonRedisSerializer 内部的
 *       ObjectMapper，配置 LocalDateTime、LocalDate、LocalTime 的序列化格式，
 *       以及忽略未知 JSON 属性的容错处理。</li>
 *   <li><b>Session 策略定制：</b>使用 {@link SaSessionForJacksonCustomized} 替代默认的
 *       SaSession，在序列化时忽略 timeout 属性。</li>
 * </ul>
 * <p>
 * 条件激活：仅当配置了 {@code spring.data.redis.host} 属性时才生效，
 * 否则 Sa-Token 默认使用内存存储。
 * </p>
 *
 * @author click33
 * @since 1.34.0
 */
@Component
// 仅当配置了 Redis 主机地址时才启用此 Bean，否则使用 Sa-Token 默认的内存存储
@ConditionalOnProperty(name = "spring.data.redis.host")
public class SaTokenDaoRedisJackson implements SaTokenDao {

    /**
     * LocalDateTime 格式化的日期时间模式
     */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * LocalDate 格式化的日期模式
     */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /**
     * LocalTime 格式化的时间模式
     */
    public static final String TIME_PATTERN = "HH:mm:ss";

    /**
     * LocalDateTime 格式化器
     */
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    /**
     * LocalDate 格式化器
     */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);

    /**
     * LocalTime 格式化器
     */
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(TIME_PATTERN);

    /**
     * ObjectMapper 对象（以 public 作用域暴露，方便开发者二次更改配置）。
     *
     * <p> 例如：
     *  <pre>
     *      SaTokenDaoRedisJackson redisJackson = (SaTokenDaoRedisJackson) SaManager.getSaTokenDao();
     *      redisJackson.objectMapper.xxx = xxx;
     *  </pre>
     * </p>
     */
    public ObjectMapper objectMapper;

    /**
     * StringRedisTemplate 实例，专门用于 String 类型的 Redis 读写操作。
     */
    public StringRedisTemplate stringRedisTemplate;

    /**
     * RedisTemplate 实例，专门用于 Object 类型的 Redis 读写操作。
     */
    public RedisTemplate<String, Object> objectRedisTemplate;

    /**
     * 标记：是否已初始化成功，防止重复初始化。
     */
    public boolean isInit;

    /**
     * 初始化方法。
     * <p>
     * 在 Spring 容器启动时，通过 {@code @Autowired} 自动注入 {@link RedisConnectionFactory}，
     * 完成 Redis 模板、序列化器、ObjectMapper 等组件的初始化配置。
     * </p>
     * <p>
     * 初始化内容包括：
     * </p>
     * <ol>
     *   <li>配置 Key 序列化器（StringRedisSerializer）和 Value 序列化器（GenericJackson2JsonRedisSerializer）。</li>
     *   <li>通过反射获取 GenericJackson2JsonRedisSerializer 内部的 ObjectMapper，并配置：
     *       <ul>
     *         <li>忽略未知 JSON 属性（FAIL_ON_UNKNOWN_PROPERTIES = false）。</li>
     *         <li>注册 JavaTimeModule，配置 LocalDateTime、LocalDate、LocalTime 的序列化/反序列化格式。</li>
     *       </ul>
     *   </li>
     *   <li>重写 SaStrategy 的 Session 创建策略，使用定制版 SaSession。</li>
     *   <li>构建并配置 StringRedisTemplate 和 RedisTemplate。</li>
     * </ol>
     *
     * @param connectionFactory Redis 连接工厂，由 Spring Boot 自动配置
     */
    @Autowired
    public void init(RedisConnectionFactory connectionFactory) {
        // 如果已经初始化成功了，就立刻退出，不重复初始化
        if(this.isInit) {
            return;
        }

        // 指定键序列化器（所有 key 都是以 String 形式存储）
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        // 指定值序列化器（使用 Jackson JSON 序列化，支持复杂对象）
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        // 通过反射获取 GenericJackson2JsonRedisSerializer 内部的 ObjectMapper 对象, 增加一些配置, 增强兼容性
        try {
            // 反射获取 GenericJackson2JsonRedisSerializer 的私有 mapper 字段
            Field field = GenericJackson2JsonRedisSerializer.class.getDeclaredField("mapper");
            field.setAccessible(true);
            this.objectMapper = (ObjectMapper) field.get(valueSerializer);

            // 配置：忽略 JSON 中存在但 Java 类不认识的未知属性，防止反序列化报错
            this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            // 配置 Java 8 时间类型的序列化和反序列化格式
            JavaTimeModule timeModule = new JavaTimeModule();

            // LocalDateTime 序列化与反序列化 —— 使用 yyyy-MM-dd HH:mm:ss 格式
            timeModule.addSerializer(new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            timeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));

            // LocalDate 序列化与反序列化 —— 使用 yyyy-MM-dd 格式
            timeModule.addSerializer(new LocalDateSerializer(DATE_FORMATTER));
            timeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));

            // LocalTime 序列化与反序列化 —— 使用 HH:mm:ss 格式
            timeModule.addSerializer(new LocalTimeSerializer(TIME_FORMATTER));
            timeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

            // 注册时间模块到 ObjectMapper
            this.objectMapper.registerModule(timeModule);

            // 重写 SaToken Session 生成策略，使用定制版 Session（忽略 timeout 属性序列化）
            SaStrategy.instance.createSession = (sessionId) -> new SaSessionForJacksonCustomized(sessionId);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }

        // 构建 StringRedisTemplate —— 用于 String 类型的读写
        StringRedisTemplate stringTemplate = new StringRedisTemplate();
        stringTemplate.setConnectionFactory(connectionFactory);
        stringTemplate.afterPropertiesSet();

        // 构建 RedisTemplate<String, Object> —— 用于 Object 类型的读写
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // 设置 key 和 hashKey 的序列化方式为 StringRedisSerializer
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        // 设置 value 和 hashValue 的序列化方式为 Jackson JSON
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();

        // 开始初始化相关组件，赋值给成员变量
        this.stringRedisTemplate = stringTemplate;
        this.objectRedisTemplate = template;

        // 打上标记，表示已经初始化成功，后续无需再重新初始化
        this.isInit = true;
    }


    /**
     * 获取指定 key 对应的 Value（String 类型），如无则返回 null。
     *
     * @param key Redis 键
     * @return 对应的字符串值，不存在则返回 null
     */
    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 写入 Value（String 类型），并设定存活时间（单位: 秒）。
     *
     * @param key     Redis 键
     * @param value   Redis 值
     * @param timeout 存活时间（秒），如果为 NEVER_EXPIRE(-1) 则永久有效
     */
    @Override
    public void set(String key, String value, long timeout) {
        // timeout 为 0 或小于等于 NOT_VALUE_EXPIRE(-2) 时不写入
        if(timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE)  {
            return;
        }
        // 判断是否为永不过期
        if(timeout == SaTokenDao.NEVER_EXPIRE) {
            stringRedisTemplate.opsForValue().set(key, value);
        } else {
            stringRedisTemplate.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 修改指定 key-value 键值对的内容（String 类型），过期时间保持不变。
     *
     * @param key   Redis 键
     * @param value 新的值
     */
    @Override
    public void update(String key, String value) {
        long expire = getTimeout(key);
        // -2 = 无此键，不执行更新
        if(expire == SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        this.set(key, value, expire);
    }

    /**
     * 删除指定的 key（String 类型）。
     *
     * @param key Redis 键
     */
    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取指定 key 的剩余存活时间（String 类型，单位: 秒）。
     *
     * @param key Redis 键
     * @return 剩余存活时间（秒），-1 表示永久，-2 表示键不存在
     */
    @Override
    public long getTimeout(String key) {
        return stringRedisTemplate.getExpire(key);
    }

    /**
     * 修改指定 key 的剩余存活时间（String 类型，单位: 秒）。
     *
     * @param key     Redis 键
     * @param timeout 新的存活时间（秒）
     */
    @Override
    public void updateTimeout(String key, long timeout) {
        // 判断是否想要设置为永久
        if(timeout == SaTokenDao.NEVER_EXPIRE) {
            long expire = getTimeout(key);
            if(expire == SaTokenDao.NEVER_EXPIRE) {
                // 如果其已经被设置为永久，则不作任何处理
            } else {
                // 如果尚未被设置为永久，那么再次 set 一次（不带过期时间即永久）
                this.set(key, this.get(key), timeout);
            }
            return;
        }
        // 设置普通过期时间
        stringRedisTemplate.expire(key, timeout, TimeUnit.SECONDS);
    }


    /**
     * 获取指定 key 对应的 Object 对象，如无则返回 null。
     *
     * @param key Redis 键
     * @return 对应的 Java 对象，不存在则返回 null
     */
    @Override
    public Object getObject(String key) {
        return objectRedisTemplate.opsForValue().get(key);
    }

    /**
     * 写入 Object 对象，并设定存活时间（单位: 秒）。
     *
     * @param key     Redis 键
     * @param object  要存储的 Java 对象
     * @param timeout 存活时间（秒），-1 表示永久
     */
    @Override
    public void setObject(String key, Object object, long timeout) {
        if(timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE)  {
            return;
        }
        // 判断是否为永不过期
        if(timeout == SaTokenDao.NEVER_EXPIRE) {
            objectRedisTemplate.opsForValue().set(key, object);
        } else {
            objectRedisTemplate.opsForValue().set(key, object, timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 更新 Object 对象的值，过期时间保持不变。
     *
     * @param key    Redis 键
     * @param object 新的 Java 对象
     */
    @Override
    public void updateObject(String key, Object object) {
        long expire = getObjectTimeout(key);
        // -2 = 无此键，不执行更新
        if(expire == SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        this.setObject(key, object, expire);
    }

    /**
     * 删除指定 key 的 Object 对象。
     *
     * @param key Redis 键
     */
    @Override
    public void deleteObject(String key) {
        objectRedisTemplate.delete(key);
    }

    /**
     * 获取指定 key 的 Object 对象的剩余存活时间（单位: 秒）。
     *
     * @param key Redis 键
     * @return 剩余存活时间（秒），-1 表示永久，-2 表示键不存在
     */
    @Override
    public long getObjectTimeout(String key) {
        return objectRedisTemplate.getExpire(key);
    }

    /**
     * 修改指定 key 的 Object 对象的剩余存活时间（单位: 秒）。
     *
     * @param key     Redis 键
     * @param timeout 新的存活时间（秒）
     */
    @Override
    public void updateObjectTimeout(String key, long timeout) {
        // 判断是否想要设置为永久
        if(timeout == SaTokenDao.NEVER_EXPIRE) {
            long expire = getObjectTimeout(key);
            if(expire == SaTokenDao.NEVER_EXPIRE) {
                // 如果其已经被设置为永久，则不作任何处理
            } else {
                // 如果尚未被设置为永久，那么再次 set 一次
                this.setObject(key, this.getObject(key), timeout);
            }
            return;
        }
        objectRedisTemplate.expire(key, timeout, TimeUnit.SECONDS);
    }


    /**
     * 搜索数据：根据前缀和关键字模糊匹配 Redis key，并返回匹配的数据列表。
     *
     * @param prefix   搜索的前缀
     * @param keyword  搜索的关键字（支持通配符）
     * @param start    分页起始位置
     * @param size     分页大小
     * @param sortType 排序类型
     * @return 匹配的数据列表
     */
    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        // 使用通配符模式匹配 Redis 中的 key
        Set<String> keys = stringRedisTemplate.keys(prefix + "*" + keyword + "*");
        List<String> list = new ArrayList<>(keys);
        // 使用 Sa-Token 工具类进行分页和排序处理
        return SaFoxUtil.searchList(list, start, size, sortType);
    }

}
