package im.tlgxy.cloud.core.config.mybatis;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.core.ResolvableType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 自定义集合类型处理器基类。
 * <p>
 * 用于处理数据库 VARCHAR 类型字段和 Java 集合类型（如 List、Set）属性之间的双向转换。
 * 数据库中以逗号分隔的字符串存储（如 "a,b,c" 或 "1,2,3"），
 * Java 实体类中对应为集合对象。
 * </p>
 * <p>
 * 特性：
 * </p>
 * <ul>
 *   <li>支持向数据库写入时将集合转换为逗号分隔的字符串。</li>
 *   <li>支持从数据库读取时自动识别数字和字符串元素，分别转换为 Integer 或 String 类型。</li>
 *   <li>通过 Spring 的 {@link ResolvableType} 解析泛型参数，自动确定集合的具体类型。</li>
 *   <li>支持 List 和 Set 两种集合类型，空值时返回对应的空集合。</li>
 * </ul>
 * <p>
 * 子类只需继承此类并指定具体的集合泛型类型即可使用。
 * </p>
 *
 * @param <T> 集合类型，例如 {@code Set<String>} 或 {@code Set<Integer>}
 * @author zhaojun
 */
// 映射到 JDBC VARCHAR 类型
@MappedJdbcTypes(JdbcType.VARCHAR)
public abstract class CollectionTypeHandler<T> extends BaseTypeHandler<Object> {

    /**
     * 将 Java 参数设置到 SQL 语句中（PreparedStatement 方式）。
     * 如果参数是 Collection 类型，则转换为逗号分隔的字符串；否则直接转换为字符串。
     *
     * @param ps        PreparedStatement 对象
     * @param i         参数位置索引（从 1 开始）
     * @param parameter Java 端参数值
     * @param jdbcType  JDBC 类型
     * @throws SQLException SQL 异常
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter instanceof Collection collection) {
            // 将集合中的每个元素转换为字符串，用逗号连接
            StringJoiner joiner = new StringJoiner(",");
            for (Object o : collection) {
                joiner.add(Convert.toStr(o));
            }
            ps.setString(i, joiner.toString());
        } else {
            // 非集合类型直接转换为字符串存储
            ps.setString(i, Convert.toStr(parameter));
        }
    }

    /**
     * 从 ResultSet 中按列名获取值并转换为 Java 集合对象。
     *
     * @param rs         结果集
     * @param columnName 列名
     * @return 转换后的 Java 集合对象
     * @throws SQLException SQL 异常
     */
    @Override
    public Object getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        String str = rs.getString(columnName);
        return convertToEntityAttribute(str);
    }

    /**
     * 从 ResultSet 中按列索引获取值并转换为 Java 集合对象。
     *
     * @param rs          结果集
     * @param columnIndex 列索引（从 1 开始）
     * @return 转换后的 Java 集合对象
     * @throws SQLException SQL 异常
     */
    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex)
            throws SQLException {
        String str = rs.getString(columnIndex);
        return convertToEntityAttribute(str);
    }

    /**
     * 从 CallableStatement（存储过程）中按列索引获取值并转换为 Java 集合对象。
     *
     * @param cs          可调用语句
     * @param columnIndex 列索引
     * @return 转换后的 Java 集合对象
     * @throws SQLException SQL 异常
     */
    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        String str = cs.getString(columnIndex);
        return convertToEntityAttribute(str);
    }

    /**
     * 集合的具体实现类（如 ArrayList.class 或 HashSet.class）
     */
    private Class<?> collectionClazz;

    /**
     * 集合中元素的类型（如 String.class 或 Integer.class）
     */
    private Type innerType;

    /**
     * 构造方法。
     * <p>
     * 通过 Spring 的 {@link ResolvableType} 反射解析当前类的泛型参数，
     * 自动获取目标集合的具体类型和元素类型，供后续转换使用。
     * </p>
     */
    public CollectionTypeHandler() {
        // 使用 ResolvableType 解析当前类中的泛型信息
        ResolvableType resolvableType = ResolvableType.forClass(getClass());
        // 获取 CollectionTypeHandler 父类的泛型类型
        Type type = resolvableType.as(CollectionTypeHandler.class).getGeneric().getType();

        if (type instanceof ParameterizedType parameterizedType) {
            // 获取集合的实现类类型（如 Set.class、List.class）
            collectionClazz = (Class<?>) parameterizedType.getRawType();
            // 获取实际类型参数（泛型参数，例如 Set<String> 中的 String）
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();

            // 获取第一个（也是唯一一个）泛型类型参数作为集合元素类型
            for (Type actualTypeArgument : actualTypeArguments) {
                innerType = actualTypeArgument;
                break;
            }
        }
    }

    /**
     * 将数据库中读取到的逗号分隔字符串转换为 Java 集合对象。
     * <p>
     * 转换规则：
     * </p>
     * <ul>
     *   <li>空字符串：根据集合类型返回对应的空集合（Collections.emptyList() 或 Collections.emptySet()）。</li>
     *   <li>非空字符串：按逗号分割后，对每个元素判断是否为数字，
     *       如果是数字则转换为 Integer，否则保留为 String。</li>
     * </ul>
     *
     * @param dbData 数据库中的原始字符串（逗号分隔格式）
     * @return 转换后的 Java 集合对象
     */
    private Object convertToEntityAttribute(String dbData) {
        // 空值处理：返回对应类型的空集合
        if (StrUtil.isEmpty(dbData)) {
            if (List.class.isAssignableFrom(collectionClazz)) {
                return Collections.emptyList();
            } else if (Set.class.isAssignableFrom(collectionClazz)) {
                return Collections.emptySet();
            } else {
                return null;
            }
        }

        // 根据集合实现类型创建对应集合实例
        Collection collection;

        if (List.class.isAssignableFrom(collectionClazz)) {
            collection = new ArrayList<>();
        } else if (Set.class.isAssignableFrom(collectionClazz)) {
            collection = new HashSet<>();
        } else {
            return null;
        }

        // 按逗号分割字符串，逐个元素进行类型判断和转换
        String[] split = dbData.split(",");
        for (String s : split) {
            if (NumberUtil.isNumber(s)) {
                // 数字字符串转换为 Integer 类型
                collection.add(Convert.convert(Integer.class, s));
            } else {
                // 非数字字符串直接保留原样
                collection.add(s);
            }
        }

        return collection;
    }

}
