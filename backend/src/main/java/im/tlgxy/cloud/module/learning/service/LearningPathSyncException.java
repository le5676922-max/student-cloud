package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;

/** The file operation already succeeded. Never retry the filesystem write on this exception. */
public class LearningPathSyncException extends BizException {
    public LearningPathSyncException() {
        super("文件位置已改变，但资料索引同步未完成。不要重复移动；请在资料库使用修复路径。");
    }
}
