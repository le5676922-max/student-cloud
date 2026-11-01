package io.studentcloud.assistant.documents;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.assistant.KnowledgeTextMatcher;
import io.studentcloud.common.ApplicationFault;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded lexical retrieval over explicitly selected immutable versions.
 * FactsPort MUST call content's current authority, never a cached ACL or intelligence replica.
 * ChunkPort MUST scope its query before recall; the second filter here is defense in depth.
 * The default application wiring has NO private source adapter and fails closed (503).
 */
public final class DocumentResearchService {
    public interface FactsPort { List<Fact> verify(Actor actor,List<String> versions); }
    public interface ChunkPort { List<Chunk> candidates(Actor actor,List<Fact> facts,int limit); }
    /** Optional administrator-configured buffered generator. Citations are untrusted DATA;
     * implementations must not execute document commands or expose Agent tools.
     */
    public interface AnswerGenerator { String answer(String query,List<Citation> citations); }
    public record Request(String query,List<String> fileVersionIds,boolean optInPrivateContext) { }
    public record Fact(String fileId,String fileVersionId,String sourceHash,long aclRevision,long sequence,
                       boolean currentVersion,boolean readable,boolean partial,boolean externalContextAllowed) { }
    public record Chunk(String id,String fileId,String fileVersionId,String sourceHash,long aclRevision,int page,String text) { }
    public record Citation(String fileId,String fileVersionId,String chunkId,int page,String excerpt) { }
    public record Revision(String fileId,String fileVersionId,String sourceHash,long aclRevision,long sequence) { }
    public record Response(String answer,String mode,String coverage,List<Citation> citations,List<Revision> sourceRevisionSet) { }
    private record Ranked(Chunk chunk,double score) { }
    private record Evidence(Actor actor,Request request,List<Fact> facts,List<Citation> citations) { }
    private final AccountDirectory accounts;
    private final FactsPort facts;
    private final ChunkPort chunks;
    private final AnswerGenerator generator;
    public DocumentResearchService(AccountDirectory accounts,FactsPort facts,ChunkPort chunks,AnswerGenerator generator) {
        this.accounts=Objects.requireNonNull(accounts);this.facts=Objects.requireNonNull(facts);
        this.chunks=Objects.requireNonNull(chunks);this.generator=generator;
    }
    /** No fallback to arbitrary host files, current path IDs, public web search or another DB. */
    public static FactsPort unavailableFacts() {
        return (actor,versions)->{throw new ApplicationFault("DOCUMENT_FACTS_UNAVAILABLE",503,"资料版本授权接口尚未接通，未读取或索引私人内容");};
    }
    public Response search(Actor actor,Request request) {
        Evidence evidence=recall(actor,request);
        recheck(evidence);
        return response(evidence,"");
    }
    /** Private answers stay buffered until the final identity/version/ACL check succeeds. */
    public Response answer(Actor actor,Request request) {
        Evidence evidence=recall(actor,request);
        recheck(evidence); // Immediately before any text can be sent to an optional model.
        String answer;
        if(evidence.citations().isEmpty()) answer="没有找到足够的可核验资料，无法回答。";
        else if(generator!=null && evidence.facts().stream().allMatch(Fact::externalContextAllowed)) {
            answer=generator.answer(evidence.request().query(),evidence.citations());
            if(answer==null || answer.isBlank() || answer.length()>12000)
                throw new ApplicationFault("DOCUMENT_ANSWER_INVALID",502,"回答未通过长度或内容校验，未发布");
        } else {
            StringBuilder text=new StringBuilder("以下仅为检索片段，未生成综合结论：");
            for(int i=0;i<evidence.citations().size();i++) text.append("\n[").append(i+1).append("] ").append(evidence.citations().get(i).excerpt());
            answer=text.toString();
        }
        recheck(evidence); // A revoked, deleted, or replaced source never yields the buffered answer.
        if(partial(evidence) && !evidence.citations().isEmpty()) answer="资料覆盖不足，请核对原文。\n"+answer;
        return response(evidence,answer);
    }
    private Evidence recall(Actor supplied,Request raw) {
        Request request=validate(raw);
        Actor actor=accounts.current(supplied);
        if(actor.workspaceId()!=null && !accounts.workspaceEnabled(actor.workspaceId())) throw denied();
        List<Fact> verified=verify(actor,request.fileVersionIds());
        var byVersion=verified.stream().collect(Collectors.toMap(Fact::fileVersionId,fact->fact));
        List<Chunk> candidates=chunks.candidates(actor,verified,200);
        if(candidates==null || candidates.size()>200) throw new ApplicationFault("DOCUMENT_RECALL_INVALID",502,"检索候选超过预算");
        var query=KnowledgeTextMatcher.query(request.query());
        var eligible=new LinkedHashMap<String,Ranked>();
        for(Chunk chunk:candidates) {
            if(chunk==null) continue;
            Fact fact=byVersion.get(chunk.fileVersionId());
            if(fact==null || !fact.fileId().equals(chunk.fileId()) || !fact.sourceHash().equals(chunk.sourceHash())
                    || fact.aclRevision()!=chunk.aclRevision()) continue;
            if(chunk.text()==null || chunk.text().length()>4000 || chunk.page()<1) continue;
            DocumentProjectionLifecycle.id(chunk.id());
            double score=KnowledgeTextMatcher.score(query,"",chunk.text());
            if(score>0) eligible.putIfAbsent(chunk.fileVersionId()+"/"+chunk.id(),new Ranked(chunk,score));
        }
        // This implementation is lexical, NOT MySQL FULLTEXT/BM25 or a connected vector store.
        List<Citation> citations=eligible.values().stream().sorted(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(hit->hit.chunk().fileVersionId()).thenComparing(hit->hit.chunk().id()))
                .limit(20).limit(8).map(hit->{var c=hit.chunk();return new Citation(c.fileId(),c.fileVersionId(),c.id(),c.page(),
                        c.text().substring(0,Math.min(c.text().length(),400)));}).toList();
        return new Evidence(actor,request,verified,citations);
    }
    private List<Fact> verify(Actor actor,List<String> versions) {
        List<Fact> result=facts.verify(actor,versions);
        if(result==null || result.size()!=versions.size()) throw denied();
        Set<String> expected=Set.copyOf(versions);
        var unique=new LinkedHashMap<String,Fact>();
        for(Fact fact:result) {
            if(fact==null || !expected.contains(fact.fileVersionId()) || unique.putIfAbsent(fact.fileVersionId(),fact)!=null) throw denied();
            DocumentProjectionLifecycle.id(fact.fileId());DocumentProjectionLifecycle.hash(fact.sourceHash());
            if(!fact.readable()) throw denied();
            if(!fact.currentVersion() || fact.aclRevision()<0 || fact.sequence()<1) throw changed();
        }
        return unique.values().stream().sorted(Comparator.comparing(Fact::fileVersionId)).toList();
    }
    private void recheck(Evidence evidence) {
        Actor current=accounts.current(evidence.actor());
        if(!current.equals(evidence.actor()) || current.workspaceId()!=null && !accounts.workspaceEnabled(current.workspaceId())) throw changed();
        if(!evidence.facts().equals(verify(current,evidence.request().fileVersionIds()))) throw changed();
    }
    private static Request validate(Request request) {
        if(request==null || request.query()==null || request.query().isBlank() || request.query().length()>2000
                || request.query().chars().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t')
                || request.fileVersionIds()==null || request.fileVersionIds().isEmpty() || request.fileVersionIds().size()>10
                || !request.optInPrivateContext()) throw new ApplicationFault("DOCUMENT_INPUT_INVALID",400,"请选择最多10份资料版本，确认使用私人上下文，问题不超过2000字");
        List<String> ids=List.copyOf(request.fileVersionIds());
        ids.forEach(DocumentProjectionLifecycle::id);
        if(Set.copyOf(ids).size()!=ids.size()) throw new ApplicationFault("DOCUMENT_INPUT_INVALID",400,"资料版本不能重复");
        return new Request(request.query().trim(),ids,true);
    }
    private static boolean partial(Evidence e) {
        return e.facts().stream().anyMatch(Fact::partial) || e.citations().stream().map(Citation::fileVersionId).distinct().count()<e.facts().size();
    }
    private static Response response(Evidence e,String answer) {
        return new Response(answer,"LEXICAL",e.citations().isEmpty()?"NO_EVIDENCE":partial(e)?"PARTIAL":"COMPLETE",e.citations(),
                e.facts().stream().map(f->new Revision(f.fileId(),f.fileVersionId(),f.sourceHash(),f.aclRevision(),f.sequence())).toList());
    }
    private static ApplicationFault denied() {return new ApplicationFault("DOCUMENT_NOT_AUTHORIZED",404,"资料不存在或当前无权读取");}
    private static ApplicationFault changed() {return new ApplicationFault("DOCUMENT_SOURCE_CHANGED",409,"资料授权或版本已变化，已丢弃本次回答，请重新选择");}
}
