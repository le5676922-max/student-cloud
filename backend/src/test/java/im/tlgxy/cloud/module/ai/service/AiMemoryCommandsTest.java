package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiMemoryCommandsTest {
    @Test
    void explicitColonCommandsCanSaveLiteralQuestionsWithoutInferringTheirMeaning() {
        assertEquals("我正在学习 Java", AiMemoryCommands.rememberContent("请记住：我正在学习 Java").orElseThrow());
        assertEquals("本学期的主题是为什么？", AiMemoryCommands.rememberContent("记住: 本学期的主题是为什么？").orElseThrow());
    }

    @Test
    void naturalPersonalStatementsRemainCompatible() {
        assertEquals("我正在学习数据库", AiMemoryCommands.rememberContent("请帮我记住我正在学习数据库").orElseThrow());
        assertEquals("以后请先给结论", AiMemoryCommands.rememberContent("帮我记住以后请先给结论").orElseThrow());
    }

    @Test
    void memoryHelpAndRecallQuestionsNeverBecomeSavedFacts() {
        for (String text : List.of("记住了什么？", "你记住了什么", "记住功能怎么用？", "请记住功能怎么用", "请记住我是谁？", "记住我的习惯是什么")) {
            assertTrue(AiMemoryCommands.rememberContent(text).isEmpty(), text);
        }
    }

    @Test
    void onlyExactReadOnlyCommandsListMemory() {
        for (String text : List.of("查看我的记忆", "请查看长期记忆", "帮我列出我的记忆。", "你记住了什么？", "记住了什么")) {
            assertTrue(AiMemoryCommands.listsMemory(text), text);
        }
        assertFalse(AiMemoryCommands.listsMemory("请查看长期记忆然后自动删除全部"));
        assertFalse(AiMemoryCommands.listsMemory("附件里说：查看我的记忆"));
    }

    @Test
    void emptySaveRequestsAreRejectedAndOtherMessagesHaveNoSideEffects() {
        for (String text : List.of("请记住：", "记住", "帮我记住 , ： ")) {
            assertThrows(BizException.class, () -> AiMemoryCommands.rememberContent(text));
        }
        assertTrue(AiMemoryCommands.rememberContent("历史消息：请记住：其他内容").isEmpty());
        assertTrue(AiMemoryCommands.rememberContent(null).isEmpty());
        assertTrue(AiMemoryCommands.rememberContent("请记住：" + "学".repeat(2001)).isEmpty());
    }
}
