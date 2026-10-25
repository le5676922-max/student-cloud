package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.learning.application.LearningLexicalRetriever;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic synthetic course evidence, not real private files, a provider benchmark or answer grading. */
class LearningRetrievalQualityTest {
    final LearningFileAccess files = mock(LearningFileAccess.class);
    final LearningLibraryService library = new LearningLibraryService(null, null, files, null, null, null);

    @Test void bodyAnswerOutranksTheSameKeywordInEveryFilenamePassage() {
        var metadataOnly = material(1, "事务原子性讲义.md", "不相关的课程安排。".repeat(600));
        var answer = material(2, "数据库笔记.md", "原子性要求事务内所有操作全部成功，失败则回滚。");
        var ranked = LearningLexicalRetriever.rank(List.of(metadataOnly, answer), Set.of("原子", "子性"), "原子性");
        assertEquals(2, ranked.getFirst().materialId());
        assertEquals(1, ranked.stream().filter(c -> c.materialId() == 1).count(), "Metadata-only matches are document fallbacks, not repeated passage evidence");
    }

    @Test void retrievesTheAnswerPassageInsteadOfAlwaysReturningTheDocumentOpening() {
        var document = material(1, "数据库事务.md", "课程安排与作业时间。".repeat(180) + "事务通过日志完成回滚，原子性要求全部成功或全部失败。");
        var ranked = LearningLexicalRetriever.rank(List.of(document), Set.of("事务", "回滚", "原子", "子性"), "事务回滚原子性");
        assertTrue(ranked.getFirst().segment() > 1);
        assertTrue(ranked.getFirst().excerpt().contains("回滚"));
        assertTrue(ranked.getFirst().reference().startsWith("M1-S"));
    }

    @Test void preservesStableStoredVectorAndCitationBoundaries() {
        String content = "a".repeat(1250) + "needle" + "z".repeat(500);
        var ranked = LearningLexicalRetriever.rank(List.of(material(1, "notes.txt", content)), Set.of("needle"), "needle");
        for (var result : ranked) {
            int start = (result.segment() - 1) * 600;
            assertEquals(content.substring(start, Math.min(start + 700, content.length())), result.excerpt());
        }
    }

    @Test void differentRelevantMaterialsAreNotCrowdedOutByOneLongDocument() {
        when(files.fingerprint(anyString(), anyString())).thenReturn("current");
        var indexed = List.of(material(1, "long.md", "事务原子性回滚".repeat(800)),
                material(2, "short.md", "事务原子性：操作要么全部提交，要么回滚。"),
                material(3, "example.md", "转账失败时事务回滚，保证原子性。"));
        var result = library.searchCandidates(indexed, Set.of("事务", "原子", "回滚"), "事务原子性回滚");
        assertEquals(5, result.citations().size());
        assertEquals(List.of(1, 2, 3), result.citations().subList(0, 3).stream().map(c -> c.materialId()).toList());
        verify(files, times(3)).fingerprint(anyString(), anyString());
        assertTrue(result.notice().contains("不是答案准确率"));
    }

    @Test void metadataOnlyFilesCannotDisplaceFurtherRelevantBodyPassages() {
        when(files.fingerprint(anyString(), anyString())).thenReturn("current");
        var indexed = new ArrayList<LearningMaterial>();
        indexed.add(material(1, "answer.md", "事务回滚保证原子性。".repeat(600)));
        for (int id = 2; id <= 5; id++) indexed.add(material(id, "事务.md", "只有课程安排，没有本次问题的答案。"));
        var result = library.searchCandidates(indexed, Set.of("事务"), "事务");
        assertEquals(5, result.citations().size());
        assertTrue(result.citations().stream().allMatch(c -> c.materialId() == 1));
        verify(files, times(1)).fingerprint("local", "/1.md");
        verifyNoMoreInteractions(files);
    }

    @Test void genuineSemanticEvidenceIsNotMistakenForMetadataOnlyFallback() {
        var document = material(1, "成本.md", "低价方案可以减少预算。");
        var bodyMatch = material(2, "notes.md", "成本控制的具体方法。");
        var semanticCitation = LearningLexicalRetriever.rank(List.of(document), Set.of("成本"), "成本").getFirst();
        var bodyCitation = LearningLexicalRetriever.rank(List.of(bodyMatch), Set.of("成本"), "成本").getFirst();
        var semantic = mock(LearningSemanticIndex.class);
        when(semantic.rank(anyList(), anyString(), anyList())).thenReturn(
                new LearningSemanticIndex.Ranking(List.of(semanticCitation, bodyCitation), "语义检索", Set.of(semanticCitation.reference())));
        org.springframework.test.util.ReflectionTestUtils.setField(library, "semantic", semantic);
        when(files.fingerprint(anyString(), anyString())).thenReturn("current");
        assertEquals(List.of(semanticCitation, bodyCitation), library.searchCandidates(List.of(document, bodyMatch), Set.of("成本"), "成本").citations());
    }

    @Test void diversityNeverSkipsLiveAuthorityOrReusesItAcrossSearches() {
        when(files.fingerprint("local", "/1.md")).thenReturn("changed");
        when(files.fingerprint("local", "/2.md")).thenReturn("current");
        var indexed = List.of(material(1, "long.md", "数据库事务".repeat(600)), material(2, "short.md", "数据库事务"));
        var result = library.searchCandidates(indexed, Set.of("事务"));
        assertEquals(1, result.skipped());
        assertEquals(List.of(2), result.citations().stream().map(c -> c.materialId()).toList());
        when(files.fingerprint("local", "/2.md")).thenThrow(new BizException("revoked"));
        assertTrue(library.searchCandidates(indexed, Set.of("事务")).citations().isEmpty());
        verify(files, times(2)).fingerprint("local", "/1.md");
        verify(files, times(2)).fingerprint("local", "/2.md");
    }

    @Test void queryNormalizationHandlesFullwidthLatinAndDropsQuestionOnlyFillers() {
        assertEquals(Set.of("java", "事务"), LearningLibraryService.terms("ＪＡＶＡ 事务"));
        assertEquals(Set.of("𠀀"), LearningLibraryService.terms("𠀀"));
        assertEquals(Set.of("𠀀学"), LearningLibraryService.terms("𠀀学"));
        assertTrue(LearningLibraryService.terms("请问 如何 怎么 怎样 什么 哪些 帮我 我想 一下").isEmpty());
        assertTrue(LearningLexicalRetriever.rank(List.of(material(1, "lecture.md", "如何开展实验")), Set.of(), "如何").isEmpty());
        var wide = LearningLexicalRetriever.rank(List.of(material(1, "lecture.md", "ＪＡＶＡ 的事务处理。")), Set.of("java"), "JAVA");
        assertEquals(1, wide.size());
        assertTrue(wide.getFirst().excerpt().startsWith("ＪＡＶＡ"), "Normalize matching, never rewrite the actual evidence");
    }

    @Test void theSameFileInTwoCoursesCannotCrowdOutAnotherRelevantFile() {
        var first = material(1, "a.md", "事务回滚");
        var duplicate = material(2, "a.md", "事务回滚");
        duplicate.setPath(first.getPath()); duplicate.setCourseId(2);
        var other = material(3, "b.md", "事务回滚");
        var ranked = LearningLexicalRetriever.rank(List.of(first, duplicate, other), Set.of("事务"), "事务");
        assertEquals(List.of(1, 3, 2), LearningLexicalRetriever.diversify(ranked).stream().map(c -> c.materialId()).toList());
    }

    @ParameterizedTest
    @CsvSource({
            "原子性回滚,1", "主键唯一约束,1", "二叉树遍历,2", "TCP重传,3", "梯度下降学习率,4", "进程调度时间片,5"
    })
    void courseFixtureReturnsTheExpectedSourceAsTopOne(String query, int expected) {
        var indexed = List.of(
                material(1, "数据库.md", "事务原子性通过回滚保持一致；主键唯一约束禁止重复值。"),
                material(2, "数据结构.md", "二叉树遍历包括先序、中序和后序。"),
                material(3, "计算机网络.md", "TCP通过确认与重传恢复丢失的报文。"),
                material(4, "机器学习.md", "梯度下降使用学习率控制参数更新的步长。"),
                material(5, "操作系统.md", "进程调度通过时间片在就绪任务之间切换。"));
        var ranked = LearningLexicalRetriever.rank(indexed, LearningLibraryService.terms(query), query);
        assertFalse(ranked.isEmpty());
        assertEquals(expected, ranked.getFirst().materialId());
    }

    @Test void unrelatedQuestionDoesNotGetAnInventedCourseCitation() {
        when(files.fingerprint(anyString(), anyString())).thenReturn("current");
        var result = library.searchCandidates(List.of(material(1, "lecture.md", "数据库事务与索引设计。")),
                LearningLibraryService.terms("火星轨道偏心率"), "火星轨道偏心率");
        assertTrue(result.citations().isEmpty());
        verifyNoInteractions(files);
    }

    private static LearningMaterial material(int id, String name, String content) {
        var material = new LearningMaterial();
        material.setId(id); material.setCourseId(1); material.setStorageKey("local");
        material.setPath("/" + id + ".md"); material.setName(name); material.setTags("");
        material.setContent(content); material.setFingerprint("current");
        return material;
    }
}
