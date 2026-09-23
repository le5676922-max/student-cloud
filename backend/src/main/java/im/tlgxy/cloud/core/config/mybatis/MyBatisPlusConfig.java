package im.tlgxy.cloud.core.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Connection;
import java.util.Locale;

/**
 * MyBatis-Plus 配置类。
 * <p>
 * 主要配置 MyBatis-Plus 的分页插件。通过从数据源连接获取数据库产品名称，
 * 自动识别并适配对应的数据库方言（MySQL 或 SQLite），确保分页查询在不同数据库下都能正确执行。
 * </p>
 * <p>
 * MyBatis-Plus 的分页插件通过拦截器机制实现，在执行 SQL 前根据数据库类型
 * 自动追加对应的分页语句（如 MySQL 的 LIMIT 子句）。
 * </p>
 *
 * @author zhaojun
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 配置 MyBatis-Plus 分页插件。
     * <p>
     * 创建 MybatisPlusInterceptor 拦截器并添加 PaginationInnerInterceptor 内部分页拦截器。
     * 通过数据源的 getDatabaseProductName() 方法动态获取数据库类型，
     * {@link DbType#getDbType(String)} 自动匹配对应的数据库方言枚举。
     * </p>
     *
     * @param dataSource 数据源，用于获取数据库连接信息以识别数据库类型
     * @return 配置了分页拦截器的 MybatisPlusInterceptor 实例
     * @throws SQLException 获取数据库连接或元数据时可能抛出的异常
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataSource dataSource) throws SQLException {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 从数据库连接获取数据库产品名称（如 "MySQL"、"SQLite"）
        String databaseProductName;
        try (Connection connection = dataSource.getConnection()) {
            databaseProductName = connection.getMetaData().getDatabaseProductName();
        }
        // 根据产品名称获取对应的 DbType 枚举
        DbType dbType = DbType.getDbType(databaseProductName.toLowerCase(Locale.ROOT));
        // 添加分页拦截器，指定数据库类型以确保分页 SQL 语法正确
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));
        return interceptor;
    }

}
