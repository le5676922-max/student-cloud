package io.studentcloud.assistant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTextMatcherTest {
    @Test void chineseQuestionMatchesRelevantWordsWithoutRequiringWholeSentence() {
        var query = KnowledgeTextMatcher.query("请问上传文件失败怎么办？");
        assertTrue(KnowledgeTextMatcher.score(query, "上传文件失败的处理方法", "核对目录写入权限和容量。") > 0);
        assertEquals(0, KnowledgeTextMatcher.score(query, "考试安排", "明天上午考试。"));
    }

    @Test void titleRelevanceBeatsIncidentalBodyMentionAndDuplicateTermsDoNotInflateScore() {
        var query = KnowledgeTextMatcher.query("upload files");
        assertTrue(KnowledgeTextMatcher.score(query, "Upload files", "Check directory permissions.")
                > KnowledgeTextMatcher.score(query, "Other topics", "You can upload files after approval."));
        assertEquals(KnowledgeTextMatcher.score(query, "Upload", "files"),
                KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("upload upload files"), "Upload", "files"));
    }

    @Test void englishWordsUseBoundariesAndUnicodeWidthIsNormalized() {
        assertEquals(0, KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("cat"), "Education", "A category of documents."));
        assertTrue(KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("ＵＰＬＯＡＤ"), "Upload", "") > 0);
        assertTrue(KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("How do I upload files?"), "Upload files", "") > 0);
    }

    @Test void analysisIsBoundedAndStopWordsDoNotInventMatches() {
        var query = KnowledgeTextMatcher.query("资料上传下载目录课程授权容量分享".repeat(180));
        assertTrue(query.terms().size() <= 64);
        assertTrue(query.phrase().length() <= KnowledgeTextMatcher.MAX_QUERY_CHARS);
        assertEquals(0, KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("how please the"), "the", "please"));
        assertEquals(0, KnowledgeTextMatcher.score(KnowledgeTextMatcher.query("？！"), "任意文件", "任意内容"));
    }
}
