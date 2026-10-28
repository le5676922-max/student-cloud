package io.studentcloud.content.remote;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.*;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

class RemoteStorageAdministrationTest {
    JdbcTemplate jdbc;
    RemoteStorageAdministration service;
    Actor global=new Actor(1,"global","global","global@example.test",Role.ADMIN,null);
    Actor admin=new Actor(2,"admin","admin","admin@example.test",Role.TENANT_ADMIN,20L);
    Actor member=new Actor(3,"member","member","member@example.test",Role.USER,20L);
    Actor outsider=new Actor(4,"other","other","other@example.test",Role.TENANT_ADMIN,21L);
    MemoryStore objects;
    int opens;
    String master=Base64.getEncoder().encodeToString(new byte[32]);
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:remote_admin_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
            new ClassPathResource("db/independent-storage-administration-schema.sql"),
            new ClassPathResource("db/independent-remote-storage-schema.sql"),
            new ClassPathResource("db/independent-remote-writes-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);objects=new MemoryStore();
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return switch((int)id){case 1->global;case 2->admin;case 3->member;case 4->outsider;default->throw new IllegalArgumentException();};}public boolean workspaceEnabled(long id){return id==20||id==21;}};
        service=new RemoteStorageAdministration(jdbc,new DataSourceTransactionManager(db),Clock.systemUTC(),accounts,
            id->id==20?List.of(admin):List.of(outsider),c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());opens++;return objects;},master,"");
    }
    @Test void verifiedSaveEncryptsCredentialsAndProvisionsOnlyTheWorkspaceAdministrator(){
        long id=service.save(admin,input("oss-one",true));
        assertTrue(objects.data.isEmpty());assertEquals(2,objects.puts);assertEquals(1,objects.cleanups);
        String encrypted=jdbc.queryForObject("SELECT credentials_ciphertext FROM sc_remote_storage",String.class);
        assertFalse(encrypted.contains("secret-value"));assertFalse(encrypted.contains("access-id"));
        assertEquals("secret-value",service.load(id).connection().secretKey());
        var dto=service.detail(admin,id);assertTrue(Boolean.TRUE.equals(dto.get("credentialsConfigured")));
        assertFalse(dto.toString().contains("secret-value"));assertFalse(dto.toString().contains("access-id"));
        assertEquals("",((Map<?,?>)dto.get("storageSourceAllParam")).get("secretKey"));
        assertEquals("/admins/u2",jdbc.queryForObject("SELECT root_path FROM sc_drive_grant WHERE mount_id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id=3",Integer.class));
        assertEquals(403,assertThrows(ApplicationFault.class,()->service.detail(outsider,id)).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->service.list(member)).httpStatus());
    }
    @Test void editKeepsBlankSecretsUsesCasAndRequiresDisableBeforeChangingTheBucket(){
        long id=service.save(admin,input("oss-one",true));
        var update=edit(id);service.save(admin,update);assertEquals("secret-value",service.load(id).connection().secretKey());
        assertEquals(409,assertThrows(ApplicationFault.class,()->service.save(admin,update)).httpStatus());
        var changed=edit(id);params(changed).put("bucketName","other-bucket");
        assertEquals("STORAGE_DISABLE_BEFORE_ROOT_CHANGE",assertThrows(ApplicationFault.class,()->service.save(admin,changed)).code());
        assertEquals("student-test",service.load(id).connection().bucket());
        service.setEnabled(admin,id,false);var changedDisabled=edit(id);params(changedDisabled).put("bucketName","other-bucket");changedDisabled.put("enable",false);
        service.save(admin,changedDisabled);assertEquals("other-bucket",service.load(id).connection().bucket());
    }
    @Test void readOnlyValidationCannotAccidentallyGrantUploads(){
        var in=input("readonly",false);long id=service.save(global,in);
        assertEquals(0,objects.puts);assertFalse(service.load(id).writeVerified());
        assertEquals("list,read",jdbc.queryForObject("SELECT permissions FROM sc_drive_grant WHERE mount_id=?",String.class,id));
    }
    @Test void workspaceAdministratorCannotReuseGloballyManagedSecretsAtAnotherLocation(){
        long id=service.save(global,input("global-owned",false));service.setEnabled(global,id,false);
        var attempted=edit(id);attempted.put("enable",false);params(attempted).put("basePath","someone-else");
        assertEquals(403,assertThrows(ApplicationFault.class,()->service.save(admin,attempted)).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->service.testConnection(admin,attempted)).httpStatus());
        assertEquals("dedicated/",service.load(id).basePrefix());
    }
    @Test void disabledSaveDoesNoNetworkAndMayNotBeEnabledWithAnUnsafeProvider(){
        var in=input("disabled",true);in.put("enable",false);long id=service.save(admin,in);
        assertEquals(0,opens);assertFalse(service.load(id).writeVerified());objects.ignoreCondition=true;
        var update=edit(id);assertEquals(409,assertThrows(ApplicationFault.class,()->service.save(admin,update)).httpStatus());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_drive_mount WHERE id=?",Boolean.class,id));
    }
    @Test void ignoredConditionalWritesOrFailedCleanupCannotPublishAConfiguration(){
        objects.ignoreCondition=true;
        assertEquals(409,assertThrows(ApplicationFault.class,()->service.save(admin,input("unsafe",true))).httpStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_remote_storage",Integer.class));
        objects.ignoreCondition=false;objects.failCleanup=true;
        var result=service.testConnection(admin,input("dirty",true));assertEquals(false,result.get("passed"));assertEquals(false,result.get("cleanup"));
        assertEquals(409,assertThrows(ApplicationFault.class,()->service.save(admin,input("dirty",true))).httpStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_remote_storage",Integer.class));
    }
    @Test void overlappingPrefixesCrossWorkspaceAndPendingWritesAreRejected(){
        long id=service.save(admin,input("prefix-one",true));var overlap=input("prefix-two",false);params(overlap).put("basePath","dedicated/nested");
        assertEquals("STORAGE_ROOT_OVERLAP",assertThrows(ApplicationFault.class,()->service.save(admin,overlap)).code());
        var cross=input("cross",false);cross.put("tenantId",21);assertEquals(403,assertThrows(ApplicationFault.class,()->service.save(admin,cross)).httpStatus());
        jdbc.update("INSERT INTO sc_remote_write_guard(mount_id,active_operation) VALUES(?,?)",id,UUID.randomUUID().toString());
        assertEquals("REMOTE_WRITE_PENDING",assertThrows(ApplicationFault.class,()->service.save(admin,edit(id))).code());
    }
    @Test void schemaUsesSupportedRendererTypes(){
        var fields=service.params(admin,"s3");
        assertEquals("switch",fields.stream().filter(f->f.get("key").equals("pathStyleAccess")).findFirst().orElseThrow().get("type"));
        assertEquals("input",fields.stream().filter(f->f.get("key").equals("secretKey")).findFirst().orElseThrow().get("type"));
    }
    @Test void disablingDuringTheNetworkProbeCannotBeUndoneByTheOlderSave(){
        long id=service.save(admin,input("disable-race",true));var olderSave=edit(id);
        objects.beforeList=()->service.setEnabled(admin,id,false);
        assertEquals("STORAGE_CHANGED",assertThrows(ApplicationFault.class,()->service.save(admin,olderSave)).code());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_drive_mount WHERE id=?",Boolean.class,id));
        assertEquals(1L,service.load(id).version());
    }
    @Test void lostProbeAcknowledgementReportsUnconfirmedCleanupInsteadOfAnUnsafeRetry(){
        objects.loseAcknowledgement=true;
        var result=service.testConnection(admin,input("lost-ack",true));
        assertEquals(false,result.get("passed"));assertEquals(false,result.get("cleanup"));
        assertTrue(result.get("message").toString().contains("未确认"));assertEquals(1,objects.data.size());
    }
    @Test void endpointPolicyRejectsCredentialsPathsAndArbitraryInternalAddresses(){
        var policy=new RemoteEndpointPolicy("");
        assertEquals(URI.create("https://oss-cn-hangzhou.aliyuncs.com"),policy.validate("oss-cn-hangzhou.aliyuncs.com","https",ObjectStore.Provider.ALIYUN_OSS));
        assertEquals(URI.create("https://s3.us-east-1.amazonaws.com"),policy.validate("https://s3.us-east-1.amazonaws.com","https",ObjectStore.Provider.S3));
        for(String endpoint:List.of("http://169.254.169.254","https://localhost","https://s3.amazonaws.com.evil.test","https://a:b@s3.amazonaws.com","https://s3.amazonaws.com/bucket","https://s3.amazonaws.com?token=secret"))assertThrows(ApplicationFault.class,()->policy.validate(endpoint,"https",ObjectStore.Provider.S3));
        assertEquals(URI.create("http://127.0.0.1:19001"),new RemoteEndpointPolicy("http://127.0.0.1:19001").validate("http://127.0.0.1:19001","http",ObjectStore.Provider.S3));
        assertThrows(ApplicationFault.class,()->new RemoteEndpointPolicy("http://10.0.0.1:9000").validate("https://s3.amazonaws.com","https",ObjectStore.Provider.S3));
    }
    @Test void cipherBindsMountAndMasterKeyAndDoesNotEchoPlaintextOnFailure(){
        var cipher=new RemoteCredentialCipher(master);var secret=new RemoteCredentialCipher.Secrets("access-id","secret-value","");
        String encrypted=cipher.encrypt(10,secret);assertNotEquals(encrypted,cipher.encrypt(10,secret));assertEquals(secret,cipher.decrypt(10,encrypted));
        assertThrows(ApplicationFault.class,()->cipher.decrypt(11,encrypted));
        byte[] different=new byte[32];different[0]=1;assertThrows(ApplicationFault.class,()->new RemoteCredentialCipher(Base64.getEncoder().encodeToString(different)).decrypt(10,encrypted));
        String broken=encrypted.substring(0,encrypted.length()-3)+"abc";assertFalse(assertThrows(ApplicationFault.class,()->cipher.decrypt(10,broken)).getMessage().contains("secret-value"));
        assertThrows(ApplicationFault.class,()->new RemoteCredentialCipher("").requireReady());assertEquals("RemoteCredentials[redacted]",secret.toString());
    }
    LinkedHashMap<String,Object> input(String key,boolean verify){
        var in=new LinkedHashMap<String,Object>();in.put("name",key);in.put("key",key);in.put("type","aliyun");in.put("tenantId",20L);in.put("enable",true);in.put("verifyWrite",verify);
        in.put("storageSourceAllParam",new LinkedHashMap<>(Map.of("endPoint","https://oss-cn-hangzhou.aliyuncs.com","bucketName","student-test","region","cn-hangzhou","basePath","dedicated","accessKey","access-id","secretKey","secret-value")));return in;
    }
    LinkedHashMap<String,Object> edit(long id){var result=new LinkedHashMap<>(service.detail(admin,id));result.put("type","aliyun");result.put("enable",true);result.put("verifyWrite",true);return result;}
    @SuppressWarnings("unchecked") Map<String,Object> params(Map<String,Object> in){return (Map<String,Object>)in.get("storageSourceAllParam");}
    static class MemoryStore implements ObjectStore {
        Map<String,byte[]> data=new HashMap<>();int puts,cleanups;boolean ignoreCondition,failCleanup,loseAcknowledgement;Runnable beforeList;
        void boundary(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
        public Page list(String p,String d,String token,int limit){boundary();if(beforeList!=null){Runnable action=beforeList;beforeList=null;action.run();}return new Page(List.of(),List.of(),null);}
        public Optional<Item> head(String k){boundary();return data.containsKey(k)?Optional.of(item(k)):Optional.empty();}
        Item item(String key){return new Item(key,data.get(key).length,Instant.now(),"etag",null);}
        public Read read(String k){boundary();return new Read(item(k),new ByteArrayInputStream(data.get(k)));}
        public void requireCreateOnly(){boundary();}
        public Receipt createOnly(String k,InputStream input,long size,String operation){boundary();puts++;if(data.containsKey(k)&&!ignoreCondition)throw new WriteFailure("DRIVE_CONFLICT",409,"同名文件",false);try{data.put(k,input.readAllBytes());}catch(IOException e){throw new UncheckedIOException(e);}if(loseAcknowledgement)throw new WriteFailure("REMOTE_UNCONFIRMED",503,"未确认",true);return new Receipt(k,size,"etag",null);}
        public void cleanupProbe(Receipt receipt){boundary();if(failCleanup)throw new IllegalStateException();cleanups++;data.remove(receipt.key());}
        public void close(){boundary();}
    }
}
