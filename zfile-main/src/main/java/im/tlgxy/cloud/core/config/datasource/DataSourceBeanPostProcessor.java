package im.tlgxy.cloud.core.config.datasource;


import cn.hutool.core.io.FileUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.zaxxer.hikari.HikariDataSource;
import im.tlgxy.cloud.core.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 数据源 Bean 后置处理器。
 * <p>
 * 在 Spring 容器初始化时对数据源相关 Bean 进行预处理，包括两个方面：
 * </p>
 * <ol>
 *   <li>
 *     <b>针对 SQLite 数据源的处理：</b>
 *     <ul>
 *       <li>在数据源初始化之前，提前创建 SQLite 数据库文件所在的目录，避免因目录不存在导致初始化失败。</li>
 *       <li>检测到版本更新时（通过 pom.xml 中的 project.version 判断），自动备份原有数据库文件，
 *           备份文件命名为 student-cloud-update-{旧版本号}-backup.db，以保证数据安全。</li>
 *     </ul>
 *   </li>
 *   <li>
 *     <b>针对 Flyway 的处理：</b>
 *     <ul>
 *       <li>根据数据库类型配置不同的 Flyway 迁移脚本目录：
 *           SQLite 使用 db/migration-sqlite，MySQL 使用 db/migration-mysql。</li>
 *     </ul>
 *   </li>
 * </ol>
 * <p>
 * 实现 {@link PriorityOrdered} 接口并设置最高优先级，确保在其他处理器之前执行。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class DataSourceBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    /**
     * TlgxyCloud 版本号配置属性 key，用于从配置文件获取当前版本
     */
    public static final String STUDENT_CLOUD_VERSION_PROPERTIES = "tongligong-student-cloud.db.version";

    /**
     * 数据库驱动类名配置属性 key
     */
    public static final String DRIVE_CLASS_NAME_PROPERTIES = "spring.datasource.driver-class-name";

    /**
     * 数据源的 Bean 名称
     */
    public static final String DATA_SOURCE_BEAN_NAME = "dataSource";

    /**
     * SQLite 数据库的 JDBC 驱动类全限定名
     */
    public static final String SQLITE_DRIVE_CLASS_NAME = "org.sqlite.JDBC";

    /**
     * MySQL 数据库的 JDBC 驱动类全限定名
     */
    public static final String MYSQL_DRIVE_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    /**
     * 在 Bean 初始化之前执行的后置处理。
     * <p>
     * 对 HikariDataSource 数据源 Bean 进行 SQLite 相关的目录创建和备份处理，
     * 对 FlywayProperties Bean 进行数据库类型对应的迁移脚本目录配置。
     * </p>
     *
     * @param bean     当前正在初始化的 Bean 实例
     * @param beanName 当前 Bean 在容器中的名称
     * @return 处理后的 Bean 实例（原样返回或经处理后返回）
     * @throws BeansException 处理过程中发生的异常
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // 如果是 HikariDataSource 数据源，进行 SQLite 相关预处理
        if (bean instanceof HikariDataSource dataSource && DATA_SOURCE_BEAN_NAME.equals(beanName)) {
            processSqliteDataSource(dataSource);
        } else if (bean instanceof FlywayProperties flywayProperties) {
            // 如果是 Flyway 配置属性 Bean，根据数据库类型设置迁移脚本目录
            processFlywayLocations(flywayProperties);
        }
        return bean;
    }

    /**
     * 处理 SQLite 数据源：提前创建数据库文件所在目录，并在版本更新时自动备份数据库文件。
     * <ol>
     *   <li>从 JDBC URL 中解析出数据库文件路径，并获取其父目录。</li>
     *   <li>如果父目录不存在，则自动创建。</li>
     *   <li>如果目录已存在且检测到版本号，则检查是否需要备份：
     *       如果备份文件不存在，则将当前数据库文件复制一份作为备份。</li>
     * </ol>
     *
     * @param dataSource HikariCP 数据源实例，包含 JDBC URL 和驱动类名信息
     */
    private void processSqliteDataSource(HikariDataSource dataSource) {
        String driverClassName = dataSource.getDriverClassName();
        String jdbcUrl = dataSource.getJdbcUrl();
        // 仅处理 SQLite 数据库
        if (StringUtils.equals(driverClassName, SQLITE_DRIVE_CLASS_NAME)) {
            // 从 JDBC URL 中提取数据库文件路径（格式如 jdbc:sqlite:/path/to/db/student-cloud.db）
            String path = jdbcUrl.replace("jdbc:sqlite:", "");
            // 获取数据库文件的父目录路径
            String folderPath = FileUtil.getAbsolutePath(new File(path).getParentFile());
            log.info("SQLite 数据库文件所在目录: [{}]", folderPath);
            File file = new File(folderPath);
            if (!file.exists()) {
                // 目录不存在，自动创建
                log.info("检测到 SQLite 数据库文件所在目录不存在, 已自动创建.");
                if (!file.mkdirs()) {
                    log.error("SQLite 数据库文件创建失败.");
                }
            } else {
                // 目录已存在
                log.info("检测到 SQLite 数据库文件所在目录已存在, 无需自动创建.");

                // 当检测到版本更新时，先自动备份数据库文件，防止数据丢失
                String version = SpringUtil.getProperty(STUDENT_CLOUD_VERSION_PROPERTIES);
                if (StringUtils.isNotEmpty(version)) {
                    // 备份文件路径
                    String backupPath = folderPath + "/student-cloud-update-" + version + "-backup.db";
                    if (!FileUtil.exist(path)) {
                        // 数据库文件不存在，说明是初始状态，无需备份
                        log.error("检测到 SQLite 数据库文件不存在, 一般为初始化状态，无需备份.");
                        return;
                    }
                    if (FileUtil.exist(backupPath)) {
                        // 备份文件已存在，跳过以避免重复备份
                        log.info("检测到 SQLite 数据库备份文件 [{}] 已存在, 无需再次备份.", backupPath);
                    } else {
                        // 执行备份操作
                        FileUtil.copy(path, backupPath, false);
                        log.info("自动备份 SQLite 数据库文件到: [{}]", backupPath);
                    }
                }
            }
        }
    }

    /**
     * 根据当前使用的数据库类型，配置 Flyway 的迁移脚本目录。
     * <ul>
     *   <li>SQLite 数据库：使用 classpath:db/migration-sqlite 目录。</li>
     *   <li>MySQL 数据库：使用 classpath:db/migration-mysql 目录。</li>
     * </ul>
     * 这样可以为不同数据库编写各自的 SQL 迁移脚本，Flyway 执行时自动选择对应的目录。
     *
     * @param flywayProperties Flyway 配置属性对象，将被修改以设置迁移脚本目录
     */
    private void processFlywayLocations(FlywayProperties flywayProperties) {
        // 从配置文件读取驱动类名，判断数据库类型
        String driveClassName = SpringUtil.getProperty(DRIVE_CLASS_NAME_PROPERTIES);
        if (SQLITE_DRIVE_CLASS_NAME.equals(driveClassName)) {
            // SQLite 数据库，使用 SQLite 专用迁移脚本目录
            flywayProperties.setLocations(List.of("classpath:db/migration-sqlite"));
        } else if (MYSQL_DRIVE_CLASS_NAME.equals(driveClassName)) {
            // MySQL 数据库，使用 MySQL 专用迁移脚本目录
            flywayProperties.setLocations(List.of("classpath:db/migration-mysql"));
        }
    }

    /**
     * 返回此后置处理器的执行顺序。
     * <p>
     * 通过设置最低的 int 值（Integer.MIN_VALUE）来确保此处理器在容器初始化时最先执行，
     * 优先于其他 BeanPostProcessor，保证数据源目录和 Flyway 配置在数据源完全初始化前完成。
     * </p>
     *
     * @return 执行顺序值，数值越小优先级越高
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

}
