package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Recognizes only explicit current-message actions, never inferred model/history instructions. */
final class AiMemoryCommands {
    private static final Pattern REMEMBER = Pattern.compile(
            "\\A(?:请帮我记住|请记住|帮我记住|记住)([\\s\\S]*)\\z");
    private static final Pattern LIST = Pattern.compile(
            "\\A(?:(?:请|帮我)?(?:查看|列出|显示)(?:我的)?(?:长期记忆|已保存的记忆|记忆)|(?:你)?(?:都)?记住了什么)[。!！?？]*\\z");
    private static final Pattern NATURAL_STATEMENT = Pattern.compile(
            "\\A(?:我|本人|以后|回答|回复|讲解|解释|输出|优先|请用|请先|使用)[\\s\\S]*\\z");
    private static final Pattern QUESTION_END = Pattern.compile(
            "(?:[?？]|什么|怎么|如何|能否|是否|是谁|叫什么|有哪些|吗|呢|么)[。!！]*\\z");

    private AiMemoryCommands() { }

    static boolean listsMemory(String message) {
        return message != null && message.length() <= 2000 && LIST.matcher(message.strip()).matches();
    }

    static Optional<String> rememberContent(String message) {
        if (message == null || message.length() > 2000 || listsMemory(message)) return Optional.empty();
        var command = REMEMBER.matcher(message.strip());
        if (!command.matches()) return Optional.empty();
        String remainder = command.group(1).stripLeading();
        boolean explicitDelimiter = remainder.startsWith(":") || remainder.startsWith("：");
        String content = remainder.replaceFirst("^[\\s:：,，]+", "").strip();
        if (content.isEmpty()) throw new BizException("请在“记住”后填写要保存的偏好或背景");
        // Questions such as “记住功能怎么用？” must not become permanent facts.
        // A colon is an unambiguous save instruction, even if the quoted fact is a question.
        if (!explicitDelimiter && (!NATURAL_STATEMENT.matcher(content).matches()
                || QUESTION_END.matcher(content).find())) return Optional.empty();
        return Optional.of(content);
    }
}
