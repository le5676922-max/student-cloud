package io.studentcloud.drive.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class LargeFileUploadTest {
    static final long MIB = 1024L * 1024;
    static final long GIB = 1024L * MIB;
    @TempDir Path temp;
    JdbcTemplate jdbc; DataSourceTransactionManager tx; MountService mounts; AccountDirectory accounts;
    Path managed, stage, courses, memberRoot;
    final Actor admin = new Actor(1,"a1","admin",null,Role.ADMIN,null);
    final Actor user = new Actor(3,"a3","member",null,Role.USER,1L);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"),ZoneOffset.UTC);
    final byte[] block = new byte[4 * 1024 * 1024];
    ResumableUploadService service;

    @BeforeEach void setup() throws Exception {
        managed=Files.createDirectory(temp.resolve("managed")); stage=Files.createDirectory(temp.resolve("stage")); courses=Files.createDirectory(temp.resolve("courses"));
        Path mounted=Files.createDirectory(managed.resolve("one")); memberRoot=Files.createDirectories(mounted.resolve("members/u3"));
        var db=new JdbcDataSource(); db.setURL("jdbc:h2:mem:large_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-uploads-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db); tx=new DataSourceTransactionManager(db);
        accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:user;}public boolean workspaceEnabled(long id){return id==1;}};
        var limits=new UploadLimits(GIB, 4*GIB, 8*GIB);
        mounts=new MountService(jdbc,tx,clock,managed,accounts,null,limits);
        var mount=mounts.createLocal(admin,"drive","local-drive",mounted,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,upload,delete',TRUE,1,1)",mount.id());
        service=new ResumableUploadService(jdbc,tx,clock,mounts,accounts,stage,managed,courses,"node-a",limits);
    }

    List<String> hashes(long size) {
        var result=new ArrayList<String>();
        for(int index=0;(long)index*block.length<size;index++) {
            seedBlock(index);
            long left=size-(long)index*block.length;
            result.add(ResumableUploadService.sha256(left>=block.length?block:Arrays.copyOf(block,(int)left)));
        }
        return result;
    }
    void seedBlock(int index) { for(int byteIndex=0;byteIndex<4;byteIndex++) block[byteIndex]=(byte)(index>>>(8*byteIndex)); }
    ResumableUploadService.View init(String name,long size) {
        var hashes=hashes(size);
        return service.init(user,"local-drive",DrivePath.of("/"+name),size,hashes,ResumableUploadService.manifestHash(hashes));
    }

    @Test void reservesFullPublicationPeakAndRejectsAnotherMaximumFileUntilCancellation() {
        var first=init("maximum.bin",GIB);
        assertEquals(4*GIB,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,first.id()));
        assertEquals(429,assertThrows(ApplicationFault.class,()->init("second.bin",1)).httpStatus());
        assertEquals("EXPIRED",service.cancel(user,first.id()).state());
        assertEquals("OPEN",init("after-cancel.bin",1).state());
    }

    @Test void unknownPublicationRetainsItsReceiptAndReservationAfterExpiry() {
        var first=init("uncertain.bin",51*MIB);
        jdbc.update("UPDATE sc_upload_session SET state='COMPLETING' WHERE id=?",first.id());
        var later=new ResumableUploadService(jdbc,tx,Clock.offset(clock,Duration.ofHours(25)),mounts,accounts,stage,managed,courses,"node-a",new UploadLimits(GIB,4*GIB,8*GIB));
        later.cleanupExpired();
        assertEquals("COMPLETING",later.status(user,first.id()).state());
        assertEquals(204*MIB,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,first.id()));
        var hashes=hashes(51*MIB);
        assertEquals(first.id(),later.init(user,"local-drive",DrivePath.of("/uncertain.bin"),51*MIB,hashes,ResumableUploadService.manifestHash(hashes)).id());
    }

    @Test void preUpgradeReservationsAreStillCountedAtTheirFullPublicationPeak() {
        var old=init("old-session.bin",MIB);
        jdbc.update("UPDATE sc_upload_session SET staging_reserved=? WHERE id=?",MIB,old.id());
        assertEquals(429,assertThrows(ApplicationFault.class,()->init("new-session.bin",GIB-MIB+1)).httpStatus());
    }

    @Test void streamsBoundaryFilesWithMatchingDigestsAndCleansAll256Chunks() throws Exception {
        for(long size:new long[]{49*MIB,50*MIB,51*MIB,128*MIB,GIB}) {
            String name="size-"+size+".bin";
            var hashes=hashes(size); var session=init(name,size);
            MessageDigest expected=MessageDigest.getInstance("SHA-256");
            for(int index=0;index<hashes.size();index++) {
                seedBlock(index);
                int count=(int)Math.min(block.length,size-(long)index*block.length);
                expected.update(block,0,count);
                service.chunk(user,session.id(),index,hashes.get(index),new ByteArrayInputStream(block,0,count));
            }
            assertEquals("COMPLETED",service.complete(user,session.id()).state());
            MessageDigest actual=MessageDigest.getInstance("SHA-256");
            try(var file=mounts.readWithMetadata(user,"local-drive",DrivePath.of("/"+name));var input=new DigestInputStream(file.input(),actual)) {
                assertEquals(size,file.metadata().size()); assertEquals(size,input.transferTo(OutputStream.nullOutputStream()));
            }
            assertArrayEquals(expected.digest(),actual.digest());
            try(var remaining=Files.list(stage.resolve(session.id()))) { assertEquals(0,remaining.count()); }
            assertEquals(0L,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
            assertEquals("COMPLETED",service.complete(user,session.id()).state());
            Files.delete(memberRoot.resolve(name));
        }
    }
}
