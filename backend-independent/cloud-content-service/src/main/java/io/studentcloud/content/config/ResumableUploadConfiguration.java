package io.studentcloud.content.config;

import io.studentcloud.drive.application.*;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class ResumableUploadConfiguration {
    @Bean @DependsOn("independentSchema")
    ResumableUploadService resumableUploads(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,DriveOperations mounts,
            @Value("${student-cloud.uploads.staging-root:}") String stage,
            @Value("${student-cloud.storage.managed-root:}") String managed,
            @Value("${student-cloud.courses.private-root:}") String course,
            @Value("${student-cloud.uploads.node-id:content-local}") String node){
        return new ResumableUploadService(jdbc,tx,clock,mounts,path(stage),path(managed),path(course),node);
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
