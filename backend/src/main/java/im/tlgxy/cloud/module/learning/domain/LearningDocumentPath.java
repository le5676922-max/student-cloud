package im.tlgxy.cloud.module.learning.domain;

import im.tlgxy.cloud.core.exception.core.BizException;

/** Pure validation of a document path relative to the caller's authorized storage root. */
public final class LearningDocumentPath {
    private LearningDocumentPath() { }

    public static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(c -> c < 32) || value.contains(":") || value.contains("%")) {
            throw new BizException("资料路径不合法");
        }
        String path = value.replace('\\', '/');
        for (String part : path.split("/")) {
            if (part.equals("..") || part.equals(".")) throw new BizException("资料路径不能包含目录跳转");
        }
        path = "/" + path.replaceAll("^/+", "").replaceAll("/+", "/");
        if (path.endsWith("/")) throw new BizException("请选择具体资料文件");
        return path;
    }
}
