package io.studentcloud.drive.application;

import io.studentcloud.drive.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class FileCatalogTest {
    JdbcTemplate jdbc;FileCatalog catalog;
    @BeforeEach void setup(){
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:catalog_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-file-catalog-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);catalog=new FileCatalog(jdbc,new DataSourceTransactionManager(ds),Clock.systemUTC());
    }
    FileRecord file(String name){return new FileRecord(DrivePath.of("/"+name),name,false,10,Instant.ofEpochMilli(100));}
    @Test void freshSnapshotAvoidsDiskRescanAndPreservesIdsAfterExplicitRefresh(){
        var reads=new AtomicInteger();var parent=DrivePath.of("/");
        var first=catalog.list(3,7,"root-version",parent,100,false,()->{reads.incrementAndGet();return List.of(file("a.txt"));});
        catalog.list(3,7,"root-version",parent,100,false,()->{reads.incrementAndGet();return List.of();});
        assertEquals(1,reads.get());
        String id=catalog.page(3,7,"root-version",parent,1,10,"a").records().getFirst().id();
        catalog.list(3,7,"root-version",parent,100,true,()->{reads.incrementAndGet();return first;});
        assertEquals(id,catalog.page(3,7,"root-version",parent,1,10,"a").records().getFirst().id());assertEquals(2,reads.get());
    }
    @Test void indexIsIsolatedByActorRootAndInvalidatedByMutation(){
        var root=DrivePath.of("/");catalog.list(3,7,"old-root",root,100,false,()->List.of(file("secret.txt")));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.page(4,7,"old-root",root,1,10,""));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.page(3,7,"new-root",root,1,10,""));
        catalog.invalidateMount(7);
        var values=catalog.list(3,7,"old-root",root,100,false,()->List.of(file("new.txt")));
        assertEquals("new.txt",values.getFirst().name());assertEquals(1,catalog.page(3,7,"old-root",root,1,10,"").total());
    }
    @Test void failedScanCannotReplaceTheLastSnapshot(){
        var root=DrivePath.of("/");catalog.list(3,7,"scope",root,100,false,()->List.of(file("a.txt")));
        assertThrows(IllegalStateException.class,()->catalog.list(3,7,"scope",root,100,true,()->{throw new IllegalStateException("disk unavailable");}));
        assertEquals(1,catalog.page(3,7,"scope",root,1,10,"").total());
    }
    @Test void persistedSnapshotPreservesSubMillisecondFileTimesForFreshnessComparisons(){
        var modified=Instant.parse("2026-10-26T00:00:00.123456789Z");
        var file=new FileRecord(DrivePath.of("/precise.txt"),"precise.txt",false,10,modified);
        var result=catalog.list(3,7,"scope",DrivePath.of("/"),100,false,()->List.of(file));
        assertEquals(modified,result.getFirst().modifiedAt());
        assertEquals(modified,catalog.page(3,7,"scope",DrivePath.of("/"),1,10,"").records().getFirst().modifiedAt());
    }
    @Test void invalidationBetweenListingAndPagingDoesNotPretendTheDirectoryIsEmpty(){
        var root=DrivePath.of("/");catalog.list(3,7,"scope",root,100,false,()->List.of(file("present.txt")));
        catalog.invalidateMount(7);
        var fault=assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.page(3,7,"scope",root,1,10,""));
        assertEquals(409,fault.httpStatus());assertEquals("CATALOG_CHANGED",fault.code());
    }
    @Test void mutationDuringScanCannotPublishOrReturnTheOldSnapshot(){
        var root=DrivePath.of("/");
        catalog.list(3,7,"scope",root,100,false,()->List.of(file("before.txt")));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.list(3,7,"scope",root,100,true,()->{
            catalog.invalidateMount(7);return List.of(file("stale.txt"));
        }));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.page(3,7,"scope",root,1,10,""));
        catalog.list(3,7,"scope",root,101,false,()->List.of(file("after.txt")));
        assertEquals("after.txt",catalog.page(3,7,"scope",root,1,10,"").records().getFirst().name());
    }
    @Test void pagingUsesLiteralNameSearchAndNeverMixesMounts(){
        var root=DrivePath.of("/");
        catalog.list(3,7,"scope",root,100,false,()->List.of(file("100%.txt"),file("100x.txt"),file("other.txt")));
        assertEquals(1,catalog.page(3,7,"scope",root,1,2,"%").total());
        assertEquals(3,catalog.page(3,7,"scope",root,2,2,"").total());
        assertEquals(1,catalog.page(3,7,"scope",root,2,2,"").records().size());
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.page(3,8,"scope",root,1,10,""));
    }
    @Test void cacheExpiresAndParentModificationRequiresAFreshScan(){
        var instant=Instant.parse("2026-10-26T00:00:00Z");
        catalog=new FileCatalog(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),Clock.fixed(instant,ZoneOffset.UTC));
        var root=DrivePath.of("/");var scans=new AtomicInteger();
        catalog.list(3,7,"scope",root,100,false,()->{scans.incrementAndGet();return List.of(file("a"));});
        catalog.list(3,7,"scope",root,101,false,()->{scans.incrementAndGet();return List.of(file("b"));});
        catalog=new FileCatalog(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),Clock.fixed(instant.plusSeconds(11),ZoneOffset.UTC));
        catalog.list(3,7,"scope",root,101,false,()->{scans.incrementAndGet();return List.of(file("c"));});
        assertEquals(3,scans.get());
    }
    @Test void newerConcurrentScanWinsEvenWhenOlderScanFinishesLast(){
        var root=DrivePath.of("/");
        var values=catalog.list(3,7,"scope",root,100,true,()->{
            catalog.list(3,7,"scope",root,100,true,()->List.of(file("fresh.txt")));
            return List.of(file("old.txt"));
        });
        assertEquals("fresh.txt",values.getFirst().name());
    }
    @Test void diskScanRunsOutsideTheSqlTransactionAndRejectsCrossDirectoryResults(){
        var root=DrivePath.of("/");
        catalog.list(3,7,"scope",root,100,false,()->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(file("one"));
        });
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.list(3,7,"scope",root,100,true,()->
                List.of(new FileRecord(DrivePath.of("/other/secret"),"secret",false,1,Instant.now()))));
        assertEquals("one",catalog.page(3,7,"scope",root,1,10,"").records().getFirst().name());
    }
    @Test void directoryCacheEvictsOldestWithoutCrossingOwnerOrDirectoryBudgets(){
        for(int index=0;index<101;index++){
            DrivePath parent=DrivePath.of("/d"+index);
            catalog.list(3,7,"scope",parent,100,false,()->List.of(new FileRecord(parent.child("one"),"one",false,1,Instant.now())));
        }
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_directory WHERE actor_id=3",Integer.class));
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_entry WHERE actor_id=3",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_directory WHERE actor_id=4",Integer.class));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->catalog.list(3,7,"scope",DrivePath.of("/"),100,true,()->
                java.util.stream.IntStream.range(0,10001).mapToObj(i->file("f"+i)).toList()));
    }
    @Test void ownerRecordBudgetEvictsAWholeOlderDirectoryRatherThanGrowingWithoutBound(){
        for(int index=0;index<6;index++){
            DrivePath parent=DrivePath.of("/d"+index);
            catalog.list(3,7,"scope",parent,100,false,()->java.util.stream.IntStream.range(0,10000)
                    .mapToObj(i->new FileRecord(parent.child("f"+i),"f"+i,false,1,Instant.ofEpochMilli(100))).toList());
        }
        assertEquals(50000,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_entry WHERE actor_id=3",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_directory WHERE actor_id=3",Integer.class));
    }
}
