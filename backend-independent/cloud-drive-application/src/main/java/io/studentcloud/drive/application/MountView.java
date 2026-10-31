package io.studentcloud.drive.application;

import java.util.Set;

/**
 * 返回存储源标识、工作空间归属与当前操作者的可用权限。
 * Connection metadata safe to expose over HTTP. Physical host paths are deliberately absent.
 */
public record MountView(long id, String key, String name, Long workspaceId, long ownerId,
                        String engine, boolean enabled, long revision, Set<String> permissions) {
    public MountView { permissions = Set.copyOf(permissions); }
}
