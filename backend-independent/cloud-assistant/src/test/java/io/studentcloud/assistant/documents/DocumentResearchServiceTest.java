package io.studentcloud.assistant.documents;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentResearchServiceTest {
    private static final String FILE="11111111-1111-1111-1111-111111111111";
    private static final String VERSION="22222222-2222-2222-2222-222222222222";
    private static final String OTHER="33333333-3333-3333-3333-333333333333";
    private static final String HASH="a".repeat(64);
    private static final Actor OWNER=new Actor(10,"SC10","owner","owner@example.test",Role.USER,100L);
    private final AccountDirectory accounts=new AccountDirectory() {
        public Actor account(long id) {return OWNER;}
        public boolean workspaceEnabled(long id) {return id==100;}
    };
    @Test void explicitlySelectedVersionsOnlyAndLexicalCoverageIsTruthfullyReported() {
        var service=new DocumentResearchService(accounts,(actor,ids)->List.of(fact(1)),(actor,facts,limit)->List.of(
            chunk(VERSION,HASH,1,"实验数据需要归一化预处理。"),
            chunk(OTHER,HASH,1,"另一个用户的实验数据需要预处理。"),
            chunk(VERSION,"b".repeat(64),1,"旧摘要实验数据")),null);
        var result=service.search(OWNER,request("实验数据预处理"));
        assertEquals("LEXICAL",result.mode());
        assertEquals("PARTIAL",result.coverage());
        assertEquals(1,result.citations().size());
        assertEquals(VERSION,result.citations().getFirst().fileVersionId());
        assertEquals("实验数据需要归一化预处理。",result.citations().getFirst().excerpt());
        assertEquals(List.of(VERSION),result.sourceRevisionSet().stream().map(DocumentResearchService.Revision::fileVersionId).toList());
    }
    @Test void revokedOrChangedSourceDuringGenerationDiscardsTheBufferedPrivateAnswer() {
        var revision=new AtomicReference<>(new DocumentResearchService.Fact(FILE,VERSION,HASH,1,1,true,true,true,true));
        var sent=new AtomicInteger();
        var service=new DocumentResearchService(accounts,(actor,ids)->List.of(revision.get()),
            (actor,facts,limit)->List.of(chunk(VERSION,HASH,1,"实验数据需要归一化预处理。")),
            (query,citations)->{sent.incrementAndGet();revision.set(fact(2));return "private generated text";});
        assertEquals("DOCUMENT_SOURCE_CHANGED",assertThrows(ApplicationFault.class,()->service.answer(OWNER,request("实验数据预处理"))).code());
        assertEquals(1,sent.get());
    }
    @Test void missingFactsOptInOrVersionBoundsFailBeforeAnyCandidateTextIsRead() {
        var reads=new AtomicInteger();
        var service=new DocumentResearchService(accounts,(actor,ids)->List.of(),(actor,facts,limit)->{reads.incrementAndGet();return List.of();},null);
        assertThrows(ApplicationFault.class,()->service.search(OWNER,request("实验")));
        assertThrows(ApplicationFault.class,()->service.search(OWNER,new DocumentResearchService.Request("实验",List.of(VERSION),false)));
        assertThrows(ApplicationFault.class,()->service.search(OWNER,new DocumentResearchService.Request("实验",List.of("C:/private.txt"),true)));
        assertThrows(ApplicationFault.class,()->service.search(OWNER,new DocumentResearchService.Request("x".repeat(2001),List.of(VERSION),true)));
        assertEquals(0,reads.get());
    }
    @Test void unconfiguredFactsAreAnUnavailableErrorNotAnEmptySuccessfulAnswer() {
        var service=new DocumentResearchService(accounts,DocumentResearchService.unavailableFacts(),
            (actor,facts,limit)->{throw new AssertionError("must not recall without facts");},null);
        var failure=assertThrows(ApplicationFault.class,()->service.answer(OWNER,request("实验")));
        assertEquals("DOCUMENT_FACTS_UNAVAILABLE",failure.code());
        assertEquals(503,failure.httpStatus());
    }
    @Test void noEvidenceCannotInventAnAnswerAndNoModelIsCalled() {
        var service=new DocumentResearchService(accounts,(actor,ids)->List.of(fact(1)),(actor,facts,limit)->List.of(),
            (query,citations)->{throw new AssertionError("no evidence must not invoke generator");});
        var result=service.answer(OWNER,request("实验"));
        assertEquals("NO_EVIDENCE",result.coverage());
        assertTrue(result.citations().isEmpty());
        assertTrue(result.answer().contains("没有找到"));
    }
    @Test void unauthorizedExternalGenerationUsesLocalEvidenceWithoutCallingGenerator() {
        var service=new DocumentResearchService(accounts,(actor,ids)->List.of(fact(1)),
            (actor,facts,limit)->List.of(chunk(VERSION,HASH,1,"实验数据需要归一化预处理。")),
            (query,citations)->{throw new AssertionError("read opt-in is NOT external model permission");});
        var result=service.answer(OWNER,request("实验数据预处理"));
        assertEquals("LEXICAL",result.mode());
        assertTrue(result.answer().contains("实验数据需要归一化预处理"));
    }
    private static DocumentResearchService.Request request(String query) {return new DocumentResearchService.Request(query,List.of(VERSION),true);}
    private static DocumentResearchService.Fact fact(long acl) {
        return new DocumentResearchService.Fact(FILE,VERSION,HASH,acl,1,true,true,true,false);
    }
    private static DocumentResearchService.Chunk chunk(String version,String hash,long acl,String text) {
        return new DocumentResearchService.Chunk("44444444-4444-4444-4444-444444444444",FILE,version,hash,acl,1,text);
    }
}
