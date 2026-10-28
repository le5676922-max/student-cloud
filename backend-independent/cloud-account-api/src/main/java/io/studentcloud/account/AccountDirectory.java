package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;

/** Identity-owned lookup contract. Business services receive this port, never identity persistence. */
public interface AccountDirectory {
    Actor account(long accountId);
    boolean workspaceEnabled(long workspaceId);

    default Actor current(Actor supplied) {
        if (supplied == null || supplied.id() < 1) throw new ApplicationFault("42000", 401, "请先登录");
        return account(supplied.id());
    }
}
