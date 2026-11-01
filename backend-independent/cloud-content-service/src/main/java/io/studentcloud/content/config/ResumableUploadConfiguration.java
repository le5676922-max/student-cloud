package io.studentcloud.content.config;

import io.studentcloud.drive.application.*;
import io.studentcloud.account.AccountDirectory;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 装配分片上传服务的暂存目录与节点标识，并调度过期会话清理。 */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class ResumableUploadConfiguration {
    @Bean @DependsOn("independentSchema")
    ResumableUploadService resumableUploads(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,DriveOperations mounts,AccountDirectory accounts,
            @Value("${student-cloud.uploads.staging-root:}") String stage,
            @Value("${student-cloud.storage.managed-root:}") String managed,
            @Value("${student-cloud.courses.private-root:}") String course,
            @Value("${student-cloud.uploads.node-id:content-local}") String node,UploadLimits uploadLimits){
        return new ResumableUploadService(jdbc,tx,clock,mounts,accounts,path(stage),path(managed),path(course),node,uploadLimits);
    }
    @Bean Cleanup uploadCleanup(ResumableUploadService uploads){return new Cleanup(uploads);}
    static final class Cleanup {
        final ResumableUploadService uploads;
        Cleanup(ResumableUploadService uploads){this.uploads=uploads;}
        @Scheduled(fixedDelay=900000,initialDelay=900000) public void clean(){uploads.cleanupExpired();}
    }
    private static Path path(String setting){
        if(setting==null||setting.isBlank())return null;
        Path path=Path.of(setting);
        if(!path.isAbsolute()||path.normalize().getParent()==null)throw new IllegalArgumentException("Configure an explicit absolute private upload directory");
        return path.normalize();
    }
}
