package io.studentcloud.drive.application;

import java.util.Set;

/** Connection metadata safe to expose over HTTP. Physical host paths are deliberately absent. */
public record MountView(long id, String key, String name, Long workspaceId, long ownerId,
                        String engine, boolean enabled, long revision, Set<String> permissions) {
    public MountView { permissions = Set.copyOf(permissions); }
}
