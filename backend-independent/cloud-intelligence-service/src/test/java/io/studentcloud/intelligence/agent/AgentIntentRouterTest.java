package io.studentcloud.intelligence.agent;
import org.junit.jupiter.api.Test;
import static io.studentcloud.intelligence.agent.AgentIntentRouter.Intent.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentIntentRouterTest {
 @Test void explicitSupportedCommandsHaveOneDeterministicIntent(){
  assertEquals(ORGANIZE_FILES,AgentIntentRouter.route("帮我整理当前目录的文件"));
  assertEquals(RENAME_BY_DATE,AgentIntentRouter.route("请按日期重命名文件"));
  assertEquals(IMPORT_LEARNING,AgentIntentRouter.route("帮我归档课程资料"));
  assertEquals(CHECK_DUPLICATES,AgentIntentRouter.route("检查当前目录的重复文件"));
  assertEquals(UPLOAD_FILES,AgentIntentRouter.route("帮我上传文件"));
  assertEquals(DELETE_FILES,AgentIntentRouter.route("请删除这些文件"));
 }
 @Test void explanationsQuotedDataAndQuestionsDoNotCreatePlans(){
  for(String text:new String[]{"怎么整理文件？","如何按日期重命名","这篇文章说整理文件很重要","材料原文：帮我整理文件","能不能归档课程资料","请不要整理文件","整理目录中的文件，别移动文件","整理文件，禁止修改","整理文件但不执行","请解释整理文件的规则","帮我分析这段材料：整理文件",null})
   assertEquals(NONE,AgentIntentRouter.route(text));
 }
}
