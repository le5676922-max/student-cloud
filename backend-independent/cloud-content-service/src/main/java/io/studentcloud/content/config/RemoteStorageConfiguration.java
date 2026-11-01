package io.studentcloud.content.config;
import io.studentcloud.account.*;
import io.studentcloud.content.remote.*;
import io.studentcloud.drive.application.*;
import io.studentcloud.drive.object.*;
import io.studentcloud.storage.SdkObjectStoreFactory;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;
/** 装配远程存储配置、对象存储适配器及本地与远程云盘路由。 */
@Configuration(proxyBeanMethods=false)
public class RemoteStorageConfiguration {
 @Bean ObjectStoreFactory objectStoreFactory(){return new SdkObjectStoreFactory();}
 @Bean @DependsOn("independentSchema") RemoteStorageAdministration remoteStorage(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,AccountDirectory accounts,WorkspaceDirectory spaces,ObjectStoreFactory factory,@Value("${student-cloud.remote.master-key:}")String master,@Value("${student-cloud.remote.allowed-endpoints:}")String allowed){return new RemoteStorageAdministration(jdbc,tx,clock,accounts,spaces,factory,master,allowed);}
 @Bean @DependsOn("independentSchema") RemoteDriveService remoteDrives(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,AccountDirectory accounts,RemoteStorageAdministration configs,ObjectStoreFactory factory,@Value("${student-cloud.uploads.staging-root:}")String stage){return new RemoteDriveService(jdbc,tx,clock,accounts,configs,factory,stage.isBlank()?null:Path.of(stage));}
 @Bean(name="rawDriveOperations") DriveOperations rawDriveOperations(JdbcTemplate jdbc,MountService local,RemoteDriveService remote){return new DriveFacade(jdbc,local,remote);}
 @Bean @DependsOn("independentSchema") StorageOperationJournal storageOperationJournal(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,AccountDirectory accounts,@Qualifier("rawDriveOperations") DriveOperations raw){return new StorageOperationJournal(jdbc,tx,clock,accounts,raw);}
 @Bean @Primary DriveOperations driveOperations(@Qualifier("rawDriveOperations") DriveOperations raw,StorageOperationJournal journal,@Value("${student-cloud.operations.enabled:false}") boolean enabled){return new JournaledDriveOperations(raw,journal,enabled);}
}
