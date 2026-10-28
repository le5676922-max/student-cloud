package io.studentcloud.account;
import java.util.List;
/** Fresh, platform-owned administrator discovery, used only to provision private roots in a workspace. */
@FunctionalInterface
public interface WorkspaceDirectory {List<Actor> administrators(long workspaceId);}
