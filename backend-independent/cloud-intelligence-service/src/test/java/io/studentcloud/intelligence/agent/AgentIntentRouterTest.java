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
 @Test void namedFilesPathsAndCustomStepsAreNotExpandedToTheWholeDirectory(){
  for(String text:new String[]{"导入a.pdf到本人学习课程","请归档Lesson.PDF到个人课程资料库",
    "整理文件：先新建archive目录，然后把a.txt复制到archive",
    "整理当前目录文件，再把笔记移动到复习目录","整理当前目录文件，按作者分类",
    "按日期重命名Report.DOCX","整理/课程/第一周目录中的文件",
    "归档\"课程笔记\"到本人学习课程","整理“期末资料”文件夹","归档课程.资料到本人学习课程",
    "organize files then copy notes to archive"})
   assertEquals(NONE,AgentIntentRouter.route(text),text);
 }
 @Test void genericBulkGoalsAndReadOnlyOrUnsupportedEntrypointsKeepTheirRules(){
  assertEquals(ORGANIZE_FILES,AgentIntentRouter.route("请整理当前目录文件"));
  assertEquals(ORGANIZE_FILES,AgentIntentRouter.route("帮我整理当前目录的所有文件，按类型分类"));
  assertEquals(ORGANIZE_FILES,AgentIntentRouter.route("organize all files in the current folder by type"));
  assertEquals(IMPORT_LEARNING,AgentIntentRouter.route("请归档本目录资料"));
  assertEquals(IMPORT_LEARNING,AgentIntentRouter.route("请归档资料到个人课程"));
  assertEquals(RENAME_BY_DATE,AgentIntentRouter.route("请按日期重命名当前目录的文件"));
  assertEquals(CHECK_DUPLICATES,AgentIntentRouter.route("查重a.pdf与b.pdf"));
  assertEquals(DELETE_FILES,AgentIntentRouter.route("整理文件后删除a.pdf"));
  assertEquals(UPLOAD_FILES,AgentIntentRouter.route("上传\"a.PDF\"到当前目录"));
 }
 @Test void negativeOrQuestionGoalsNeverBecomeBulkPreviews(){
  for(String text:new String[]{"请不要归档a.PDF到学习课程","整理文件但不能执行","整理文件但不可移动",
    "整理文件，不许修改","整理文件但不准执行","整理文件然后不再归档资料","整理文件并非要求执行",
    "整理当前目录文件可以么","Would you organize files","organize files, don't execute"})
   assertEquals(NONE,AgentIntentRouter.route(text),text);
 }
}
