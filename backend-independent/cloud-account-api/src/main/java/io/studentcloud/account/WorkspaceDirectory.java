package io.studentcloud.account;
import java.util.List;
/** 提供空间当前可用管理员的发现端口。 Fresh, platform-owned administrator discovery, used only to provision private roots in a workspace. */
@FunctionalInterface
public interface WorkspaceDirectory {List<Actor> administrators(long workspaceId);}
