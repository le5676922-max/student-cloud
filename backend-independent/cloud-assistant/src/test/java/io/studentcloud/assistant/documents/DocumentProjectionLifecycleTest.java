package io.studentcloud.assistant.documents;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class DocumentProjectionLifecycleTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private DocumentProjectionLifecycle indexes;
    private static final String FILE="11111111-1111-1111-1111-111111111111";
    private static final String V1="22222222-2222-2222-2222-222222222222";
    private static final String V2="33333333-3333-3333-3333-333333333333";
    private static final String HASH="a".repeat(64);
    @BeforeEach void database() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:projection_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-document-projection-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db); tx=new DataSourceTransactionManager(db);
        indexes=new DocumentProjectionLifecycle(jdbc,tx,Clock.systemUTC());
    }
    @Test void newOldDuplicateDeleteAndLateCompletionCannotResurrectContentAfterRestart() {
        assertTrue(indexes.consume(event("new",2,V2,false)));
        assertFalse(indexes.consume(event("old",1,V1,false)));
        assertFalse(indexes.consume(event("new",2,V2,false)));
        var lease=indexes.begin(FILE,2,HASH,"parser-1");
        assertEquals("EXTRACTING",indexes.get(FILE).state());
        assertTrue(indexes.consume(event("delete",3,V2,true)));
        assertFalse(indexes.publish(lease,1,1,false));
        assertFalse(indexes.consume(event("late-upsert",2,V2,false)));
        var restarted=new DocumentProjectionLifecycle(jdbc,tx,Clock.systemUTC());
        assertEquals("DELETED",restarted.get(FILE).state());
        assertEquals(3,restarted.get(FILE).sequence());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM sc_document_consumed_event",Integer.class));
    }
    @Test void sourceVersionDigestAndGenerationFenceAreAllBoundToThePublishedResult() {
        indexes.consume(event("first",1,V1,false));
        var old=indexes.begin(FILE,1,HASH,"parser-1");
        assertThrows(ApplicationFault.class,()->indexes.begin(FILE,1,"b".repeat(64),"parser-1"));
        var replacement=indexes.begin(FILE,1,HASH,"parser-1");
        assertFalse(indexes.publish(old,2,2,false));
        assertTrue(indexes.publish(replacement,2,1,true));
        assertEquals("PARTIAL",indexes.get(FILE).state());
        indexes.consume(event("version-changed",2,V2,false));
        assertEquals("PENDING",indexes.get(FILE).state());
        assertFalse(indexes.publish(replacement,2,2,false));
    }
    @Test void incompatibleEventsAndMismatchedSequenceNeverMutateTheProjection() {
        var bad = new DocumentProjectionLifecycle.Event(2,"bad",FILE,V1,1,"PRIVATE",10,1,HASH,false);
        assertEquals("DOCUMENT_EVENT_SCHEMA_UNSUPPORTED",assertThrows(ApplicationFault.class,()->indexes.consume(bad)).code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_document_projection",Integer.class));
        indexes.consume(event("first",1,V1,false));
        assertFalse(indexes.consume(event("ambiguous-same-sequence",1,V2,false)));
        assertEquals(V1,indexes.get(FILE).fileVersionId());
        assertThrows(ApplicationFault.class,()->indexes.begin(FILE,2,HASH,"parser-1"));
    }
    private static DocumentProjectionLifecycle.Event event(String id,long seq,String version,boolean deleted) {
        return new DocumentProjectionLifecycle.Event(1,id,FILE,version,seq,"PRIVATE",10,seq,HASH,deleted);
    }
}
