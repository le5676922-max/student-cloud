package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMemberQueryTest {
    private RecordingJdbc jdbc;
    private WorkspaceMemberQuery query;
    private Actor owner;
    private Actor other;

    @BeforeEach void setup() {
        var database=new DriverManagerDataSource("jdbc:h2:mem:member_query_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql"),
                new ClassPathResource("db/independent-public-profile-schema.sql")).execute(database);
        jdbc=new RecordingJdbc(database);
        var manager=new DataSourceTransactionManager(database);
        Clock clock=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC);
        query=new WorkspaceMemberQuery(jdbc,new AccountAdministration(jdbc,manager,clock));
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'one',TRUE,1),(2,'two',TRUE,1)");
        account(1,Role.TENANT_ADMIN,1L,true);
        account(2,Role.TENANT_ADMIN,2L,true);
        account(3,Role.ADMIN,null,true);
        owner=actor(1,Role.TENANT_ADMIN,1L);
        other=actor(2,Role.TENANT_ADMIN,2L);
    }

    @Test void fillsProfilesAndMembershipStatesInOneBatchWithoutLeakingAnotherWorkspace() {
        account(10,Role.USER,1L,true);
        account(11,Role.USER,1L,false);
        account(12,Role.USER,1L,false);
        account(13,Role.USER,1L,true);
        account(14,Role.USER,1L,true);
        account(20,Role.USER,2L,true);
        jdbc.update("INSERT INTO sc_public_profile(account_id,nickname,email_verified_at) VALUES(10,'Display name',1),(11,'   ',1),(20,'Private other profile',1)");
        request(11,1,"PENDING");
        request(12,1,"REJECTED");
        request(13,1,"APPROVED");
        // An inconsistent request pointing elsewhere must not influence this workspace's result.
        request(14,2,"REJECTED");
        request(20,2,"PENDING");
        var page=query.page(owner,1,20,"");
        assertEquals(5,page.total());
        assertEquals(1,page.page());
        assertEquals(5,page.items().size());
        assertEquals("Display name",member(page,10).nickname());
        assertEquals("member-11",member(page,11).nickname());
        assertEquals("member-12",member(page,12).nickname());
        assertEquals("ACTIVE",member(page,10).membershipStatus());
        assertEquals("PENDING",member(page,11).membershipStatus());
        assertEquals("REJECTED",member(page,12).membershipStatus());
        assertEquals("ACTIVE",member(page,13).membershipStatus());
        assertEquals("ACTIVE",member(page,14).membershipStatus());
        assertTrue(page.items().stream().allMatch(row->Long.valueOf(1).equals(row.tenantId()) && row.role()==Role.USER));
        assertTrue(page.items().stream().noneMatch(row->row.id()==20 || "Private other profile".equals(row.nickname())));
        assertEquals(1,jdbc.enrichments.size());
        assertEquals(List.of(1L,10L,11L,12L,13L,14L),jdbc.enrichments.getFirst().parameters());
    }

    @Test void aFullPageStillUsesOneParameterizedBatchAndSearchRemainsScoped() {
        for(long id=10;id<115;id++) account(id,Role.USER,1L,true);
        account(200,Role.USER,2L,true);
        var first=query.page(owner,1,100,"");
        assertEquals(105,first.total());
        assertEquals(100,first.items().size());
        assertEquals(1,jdbc.enrichments.size());
        var batch=jdbc.enrichments.getFirst();
        assertEquals(101,batch.parameters().size());
        assertEquals(101,batch.sql().chars().filter(ch->ch=='?').count());
        assertFalse(batch.sql().contains("member-"));
        jdbc.enrichments.clear();
        var searched=query.page(owner,1,20,"member-200");
        assertEquals(0,searched.total());
        assertTrue(searched.items().isEmpty());
        assertTrue(jdbc.enrichments.isEmpty());
        var second=query.page(owner,2,100,"");
        assertEquals(5,second.items().size());
        assertEquals(1,jdbc.enrichments.size());
        assertEquals(6,jdbc.enrichments.getFirst().parameters().size());
    }

    @Test void emptyPagesNeverRunProfileOrEnrollmentQueries() {
        var empty=query.page(owner,1,20,"");
        assertEquals(0,empty.total());
        assertTrue(empty.items().isEmpty());
        assertTrue(jdbc.enrichments.isEmpty());
        assertThrows(UnsupportedOperationException.class,()->empty.items().add(null));
        assertEquals(400,assertThrows(ApplicationFault.class,()->query.page(owner,1,101,"")).httpStatus());
        assertEquals(400,assertThrows(ApplicationFault.class,()->query.page(owner,Integer.MAX_VALUE,100,"")).httpStatus());
    }

    @Test void scopeAndCurrentRoleAreCheckedBeforeAnyEnrichment() {
        account(10,Role.USER,1L,true);
        var fake=new Actor(owner.id(),owner.accountId(),owner.username(),owner.email(),Role.TENANT_ADMIN,2L);
        assertEquals(403,assertThrows(ApplicationFault.class,()->query.page(fake,1,20,"")).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->query.page(actor(3,Role.ADMIN,null),1,20,"")).httpStatus());
        assertTrue(jdbc.enrichments.isEmpty());
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=1");
        assertEquals(401,assertThrows(ApplicationFault.class,()->query.page(owner,1,20,"")).httpStatus());
        assertTrue(jdbc.enrichments.isEmpty());
        assertTrue(query.page(other,1,20,"").items().isEmpty());
    }

    private WorkspaceMemberQuery.Member member(WorkspaceMemberQuery.Page page,long id) {
        return page.items().stream().filter(row->row.id()==id).findFirst().orElseThrow();
    }
    private void account(long id,Role role,Long workspace,boolean enabled) {
        jdbc.update("INSERT INTO sc_account(id,account_id,username,email,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,?,?,?,?,?,1,1)",
                id,"SCMEMBER"+id,"member-"+id,"member-"+id+"@example.invalid","not-used",role.name(),workspace,enabled);
    }
    private Actor actor(long id,Role role,Long workspace) {return new Actor(id,"SCMEMBER"+id,"member-"+id,"member-"+id+"@example.invalid",role,workspace);}
    private void request(long accountId,long workspace,String state) {
        jdbc.update("INSERT INTO sc_workspace_join_request(workspace_id,account_id,status,created_at) VALUES(?,?,?,1)",workspace,accountId,state);
    }
    private record Recorded(String sql,List<Object> parameters) { }
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<Recorded> enrichments=new ArrayList<>();
        RecordingJdbc(DriverManagerDataSource source) {super(source);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... arguments) {
            if(sql.contains("LEFT JOIN sc_public_profile")) enrichments.add(new Recorded(sql,List.copyOf(Arrays.asList(arguments.clone()))));
            return super.query(sql,mapper,arguments);
        }
    }
}
