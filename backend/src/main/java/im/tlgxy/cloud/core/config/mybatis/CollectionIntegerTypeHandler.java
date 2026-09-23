package im.tlgxy.cloud.core.config.mybatis;

import java.util.Set;

/**
 * Integer 集合类型处理器。
 * <p>
 * 继承自 {@link CollectionTypeHandler}，专门处理数据库 VARCHAR 字段与 Java {@code Set<Integer>}
 * 类型属性之间的转换。数据库中存储的是逗号分隔的整数字符串（如 "1,2,3"），
 * Java 实体类中对应的是一个 {@link Set}{@code <Integer>} 集合。
 * </p>
 * <p>
 * 父类 {@link CollectionTypeHandler} 已实现了完整的转换逻辑（使用泛型参数化），
 * 子类只需指定具体类型即可。
 * </p>
 */
public class CollectionIntegerTypeHandler extends CollectionTypeHandler<Set<Integer>> {

}
