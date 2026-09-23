package im.tlgxy.cloud.core.config.mybatis;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * MyBatis 数据库 ID 提供者。
 * <p>
 * 实现了 {@link DatabaseIdProvider} 接口，用于动态检测当前使用的数据库类型，
 * 从而在 MyBatis XML 映射文件中根据数据库类型执行不同的 SQL 语句。
 * </p>
 * <p>
 * 使用方式：
 * </p>
 * <ul>
 *   <li>在 XML 的 SQL 语句中判断：<br>
 *       <code>&lt;if test="_databaseId == 'mysql'"&gt;</code></li>
 *   <li>在外层指定 databaseId 属性：<br>
 *       <code>&lt;delete id="xxx" databaseId="sqlite"&gt;</code></li>
 * </ul>
 * <p>
 * 这样可以在一个映射文件中同时维护 MySQL 和 SQLite 两套 SQL 语句，
 * MyBatis 在运行时会自动选择匹配的语句执行。
 * </p>
 *
 * @author zhaojun
 */
@Component
public class MyDatabaseIdProvider implements DatabaseIdProvider {

    /**
     * MySQL 数据库产品名称常量（由 JDBC 驱动返回）
     */
    private static final String DATABASE_MYSQL = "MySQL";

    /**
     * SQLite 数据库产品名称常量（由 JDBC 驱动返回）
     */
    private static final String DATABASE_SQLITE = "SQLite";

    /**
     * 根据数据源获取当前数据库的标识别名。
     * <p>
     * 通过数据源连接获取数据库元数据中的产品名称（DatabaseProductName），
     * 然后映射为简短的别名（小写），供 MyBatis 在 XML 映射文件中使用。
     * </p>
     *
     * @param dataSource 数据源，提供数据库连接
     * @return 数据库别名："mysql" 表示 MySQL 数据库，"sqlite" 表示 SQLite 数据库，空字符串表示未知类型
     * @throws SQLException 获取数据库连接或元数据时可能抛出的异常
     */
    @Override
    public String getDatabaseId(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return switch (connection.getMetaData().getDatabaseProductName()) {
                case DATABASE_MYSQL -> "mysql";
                case DATABASE_SQLITE -> "sqlite";
                default -> "";
            };
        }
    }

}
