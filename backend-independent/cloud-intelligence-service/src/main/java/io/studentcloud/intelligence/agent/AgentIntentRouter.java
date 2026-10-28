package io.studentcloud.intelligence.agent;

import java.util.Locale;

/** Deterministic routing for supported explicit user commands, never instructions from retrieved documents. */
public final class AgentIntentRouter {
    private AgentIntentRouter() { }
    public enum Intent { NONE, ORGANIZE_FILES, RENAME_BY_DATE, IMPORT_LEARNING, CHECK_DUPLICATES, UPLOAD_FILES, DELETE_FILES }
    public static Intent route(String message) {
        if(message==null)return Intent.NONE;
        String text=message.strip().toLowerCase(Locale.ROOT);
        // Questions about a feature are answered from help; mentioning an operation is not consent to start a plan.
        if(text.matches("(?s).*(怎么|如何|为什么|是什么|能否|是否|能不能|可以吗|怎样|\\?|？).*"))return Intent.NONE;
        if(text.matches("(?s).*(不要|别再|别帮|别\\s*(?:移动|删除|整理|修改|上传|执行|重命名|操作)|禁止|不(?:移动|删除|执行|修改|上传|整理)|不想|不用|无需|取消|停止|do not|don't|never).*"))return Intent.NONE;
        for(int i=0;i<4;i++){
            String stripped=text.replaceFirst("^(?:请|麻烦你?|帮我|替我|为我|小芽[，,\\s]*)\\s*","");
            if(stripped.equals(text))break;text=stripped;
        }
        boolean explicit=text.matches("(?s)^(整理|分类|按日期|按时间|查找|检查|查重|找出|归档|导入|上传|删除|清空|organize\\b|rename\\b|find duplicates\\b|check duplicates\\b|upload\\b|delete\\b).*" );
        if(!explicit)return Intent.NONE;
        if(text.matches("(?s).*(删除|清空|delete).*"))return Intent.DELETE_FILES;
        if(text.matches("(?s).*(上传|upload).*"))return Intent.UPLOAD_FILES;
        if(text.matches("(?s).*(重复文件|重复资料|文件查重|查重|duplicates).*"))return Intent.CHECK_DUPLICATES;
        if(text.matches("(?s).*(归档|导入).*(课程|资料|学习).*"))return Intent.IMPORT_LEARNING;
        if(text.matches("(?s).*(日期|时间).*(命名|rename).*"))return Intent.RENAME_BY_DATE;
        if(text.matches("(?s).*(整理|分类|organize).*(文件|目录|资料|files|folder).*"))return Intent.ORGANIZE_FILES;
        return Intent.NONE;
    }
}
