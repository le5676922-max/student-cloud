package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult.SupportSuggestion;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure intent detection; this class cannot create conversations or disclose chat history. */
final class AiSupportSuggestions {
    private static final Pattern HUMAN = Pattern.compile("人工客服|人工服务|真人客服|人工支持|转(?:接)?(?:到)?人工|联系全局管理员");
    private static final Pattern DECLINE = Pattern.compile("(?:不要|不用|无需|不需要|不想|暂不|先不|别|取消).{0,8}(?:人工|客服)|(?:人工|客服).{0,6}(?:不要|不用|不需要|取消)");
    private static final Pattern FEEDBACK = Pattern.compile(
            "^(?:还是|仍然|依然)?(?:没用|不行|不对|没解决|没有解决|没有帮助|没帮助|没帮到我|并没有帮到我|回答有问题|回答错误|答非所问|不是这个问题)"
                    + "|(?:你的|你刚才的|你这次的|你这|你|刚才的|上面的|这个|这次的)(?:回答|答案|方法|建议|步骤|回复|解释|说的).{0,12}(?:不对|有问题|错误|没用|没有帮助|没帮助|没解决|没有解决)"
                    + "|(?:还是|仍然|依然)(?:不行|没用|没解决|没有解决)"
                    + "|(?:你|你根本|你并).{0,5}(?:没有|没能|没)(?:帮我)?解决(?:我的)?问题");

    private AiSupportSuggestions() { }

    static SupportSuggestion forRequest(AiChatRequest request, KnowledgeAudience audience) {
        if (audience != KnowledgeAudience.USER || request == null || request.getMessage() == null) return null;
        String text = request.getMessage().trim().toLowerCase(Locale.ROOT).replaceAll("[\\t\\p{Zs}]+", "");
        Boolean humanIntent = explicitIntent(text);
        if (Boolean.FALSE.equals(humanIntent)) return null;
        if (Boolean.TRUE.equals(humanIntent)) return new SupportSuggestion("你希望由人工客服继续处理。");
        // First feedback should get a better answer, rather than immediately routing away.
        if (text.length() <= 160 && FEEDBACK.matcher(text).find() && maySuggest(request)) {
            return new SupportSuggestion("前面的回答没有解决你的问题，可以请全局管理员继续协助。");
        }
        return null;
    }

    static boolean maySuggest(AiChatRequest request) {
        if (request == null || request.getMessage() == null || declinesSupport(request)
                || request.getHistory() == null) return false;
        var recent = request.getHistory().subList(Math.max(0, request.getHistory().size() - 6), request.getHistory().size());
        long answers = recent.stream().filter(item -> item != null
                && "assistant".equals(item.getRole()) && item.getContent() != null
                && !item.getContent().isBlank()).count();
        boolean priorFeedback = recent.stream().anyMatch(item -> item != null
                && "user".equals(item.getRole()) && item.getContent() != null
                && FEEDBACK.matcher(item.getContent().strip()).find());
        return answers >= 2 && priorFeedback;
    }

    static boolean declinesSupport(AiChatRequest request) {
        return request != null && request.getMessage() != null
                && Boolean.FALSE.equals(explicitIntent(request.getMessage().replaceAll("[\\t\\p{Zs}]+", "")));
    }

    private static Boolean explicitIntent(String text) {
        // Evaluate clauses in order: “不要 AI，转人工” is different from “不要转人工”。
        Boolean intent = null;
        for (String clause : text.split("[，,。.!！?？;；\\n]")) {
            if (HUMAN.matcher(clause).find()) intent = !DECLINE.matcher(clause).find();
        }
        return intent;
    }

    static String modelReason(String value) {
        if (value == null || value.isBlank()) return "这个问题需要人工进一步协助。";
        String plain = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (plain.isBlank()) return "这个问题需要人工进一步协助。";
        return plain.codePointCount(0, plain.length()) > 240
                ? plain.substring(0, plain.offsetByCodePoints(0, 240)) : plain;
    }
}
