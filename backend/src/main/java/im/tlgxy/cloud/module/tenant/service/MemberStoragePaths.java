package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import java.util.Arrays;

/** Personal roots are stable numeric identities, never nicknames or email addresses. */
public final class MemberStoragePaths {
    private MemberStoragePaths() { }
    public static String normalize(String path) {
        if (path == null || path.length() > 1024 || !path.startsWith("/") || path.contains("\\")
                || path.contains("%") || path.chars().anyMatch(Character::isISOControl))
            throw new BizException("目录格式不正确");
        var parts = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
        if (parts.stream().anyMatch(p -> p.equals(".") || p.equals(".."))) throw new BizException("目录不能包含相对路径");
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }
    public static String personalRoot(String managerRoot, int member) {
        String root = normalize(managerRoot);
        if (member <= 2) throw new BizException("不能为系统账号建立成员目录");
        return (root.equals("/") ? "" : root) + "/members/u" + member;
    }
    public static boolean within(String child, String parent) {
        String path = normalize(child), root = normalize(parent);
        return root.equals("/") || path.equals(root) || path.startsWith(root + "/");
    }
    public static boolean personal(String path, int member) {
        String normalized = normalize(path), marker = "/members/u" + member;
        int at = normalized.indexOf(marker);
        return at >= 0 && (normalized.length() == at + marker.length()
                || normalized.charAt(at + marker.length()) == '/');
    }
}
