package io.studentcloud.content.config;

import io.studentcloud.account.AccountDirectory;

import io.studentcloud.course.SharedCourseService;
import io.studentcloud.course.CourseMaterialService;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import io.studentcloud.drive.application.WorkspaceCloudService;
import io.studentcloud.drive.application.FileCatalog;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean @DependsOn("independentSchema")
    FileCatalog fileCatalog(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock){return new FileCatalog(jdbc,tx,clock);}
    @Bean
    @DependsOn("independentSchema")
    io.studentcloud.content.learning.LearningLibraryService learningLibrary(JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, DriveOperations mounts) {
        return new io.studentcloud.content.learning.LearningLibraryService(jdbc, transactions, clock, accounts, mounts);
    }
    @Bean
    @DependsOn("independentSchema")
    MountService mounts(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                        @Value("${student-cloud.storage.managed-root:}") String configuredRoot, AccountDirectory accounts,FileCatalog catalog) {
        return new MountService(jdbc, transactions, clock, configuredPath(configuredRoot), accounts,catalog);
    }

    @Bean
    @DependsOn("independentSchema")
    WorkspaceCloudService cloudAccess(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                                     @Value("${student-cloud.storage.managed-root:}") String configuredRoot, AccountDirectory accounts) {
        return new WorkspaceCloudService(jdbc, transactions, clock, configuredPath(configuredRoot), accounts);
    }

    @Bean
    @DependsOn("independentSchema")
    SharedCourseService courses(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts) {
        return new SharedCourseService(jdbc, transactions, clock, accounts);
    }

    @Bean
    @DependsOn("independentSchema")
    CourseMaterialService courseMaterials(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                                         SharedCourseService courses,
                                         @Value("${student-cloud.storage.managed-root:}") String driveSetting,
                                         @Value("${student-cloud.courses.private-root:}") String courseSetting) {
        Path driveRoot = configuredPath(driveSetting);
        Path courseRoot = configuredPath(courseSetting);
        if (driveRoot != null && courseRoot != null && (courseRoot.startsWith(driveRoot) || driveRoot.startsWith(courseRoot))) {
            throw new IllegalArgumentException("Private course storage and browsable drive storage must be separate directories");
        }
        return new CourseMaterialService(jdbc, transactions, clock, courses, courseRoot);
    }

    private static Path configuredPath(String setting) {
        if (setting.isBlank()) return null;
        Path root = Path.of(setting);
        if (!root.isAbsolute() || root.normalize().getParent() == null) throw new IllegalArgumentException("Configure an explicit private storage directory");
        return root.normalize();
    }
}
