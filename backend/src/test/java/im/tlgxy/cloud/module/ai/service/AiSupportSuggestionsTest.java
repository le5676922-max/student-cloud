package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiSupportSuggestionsTest {
    @Test
    void explicitRequestsOfferConfirmationWithoutNeedingPreviousMessages() {
        for (String question : List.of("人工服务", "请帮我转人工", "转接人工客服", "我想联系全局管理员", "不要 AI，转人工", "不要AI\n转人工", "先不要转人工，算了还是转人工")) {
            assertNotNull(AiSupportSuggestions.forRequest(request(question, false), KnowledgeAudience.USER), question);
        }
    }

    @Test
    void declineAndNormalArtificialIntelligenceQuestionsDoNotOfferSupport() {
        for (String question : List.of("不要转人工", "不需要人工服务", "暂时不用人工客服", "别联系人工客服", "取消转人工", "人工智能是什么", "人工智能如何帮助文件整理", "怎么上传文件")) {
            assertNull(AiSupportSuggestions.forRequest(request(question, true), KnowledgeAudience.USER), question);
        }
    }

    @Test
    void unresolvedFeedbackRequiresAPreviousAssistantAnswer() {
        for (String question : List.of("没解决", "还是不行", "你的回答没有帮助", "这个方法有问题", "我按步骤操作还是不行", "你根本没有解决我的问题", "答非所问", "回答有问题", "没帮助", "我觉得你回答不对")) {
            assertNull(AiSupportSuggestions.forRequest(request(question, true), KnowledgeAudience.USER), "One answer must not force handoff: " + question);
            assertNull(AiSupportSuggestions.forRequest(request(question, false), KnowledgeAudience.USER), question);
        }
    }

    @Test
    void repeatedUnresolvedFeedbackCanOfferHumanHelpAfterAnotherAttempt() {
        AiChatRequest request = request("还是不行", true);
        var feedback = new AiChatRequest.ChatMessage(); feedback.setRole("user"); feedback.setContent("没有解决");
        var attempt = new AiChatRequest.ChatMessage(); attempt.setRole("assistant"); attempt.setContent("请检查上传权限后重试。");
        request.setHistory(List.of(request.getHistory().getFirst(), feedback, attempt));
        assertNotNull(AiSupportSuggestions.forRequest(request, KnowledgeAudience.USER));
        request.setMessage("不需要人工客服");
        assertNull(AiSupportSuggestions.forRequest(request, KnowledgeAudience.USER));
    }

    @Test
    void ordinaryCourseQuestionsAndGlobalAdministratorsDoNotTrigger() {
        for (String question : List.of("数据库有什么用", "请解释人工智能", "老师为什么说答非所问", "如何避免回复没有帮助")) {
            assertNull(AiSupportSuggestions.forRequest(request(question, true), KnowledgeAudience.USER), question);
        }
        assertNull(AiSupportSuggestions.forRequest(request("转人工", true), KnowledgeAudience.ADMIN));
    }

    @Test
    void modelReasonIsPlainBoundedAndDoesNotCutUnicodeCharacters() {
        assertEquals("这个问题需要人工进一步协助。", AiSupportSuggestions.modelReason("\n\t"));
        assertEquals("原因  后续", AiSupportSuggestions.modelReason("原因\n\t后续"));
        String reason = AiSupportSuggestions.modelReason("🌱".repeat(300));
        assertEquals(240, reason.codePointCount(0, reason.length()));
        assertFalse(Character.isHighSurrogate(reason.charAt(reason.length() - 1)));
    }

    private AiChatRequest request(String message, boolean hasAnswer) {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(message);
        if (hasAnswer) {
            var answer = new AiChatRequest.ChatMessage();
            answer.setRole("assistant");
            answer.setContent("请检查上传权限后重试。");
            request.setHistory(List.of(answer));
        }
        return request;
    }
}
