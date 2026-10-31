package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;

/** 提供当前账号身份与空间可用状态的查询端口。 Identity-owned lookup contract. Business services receive this port, never identity persistence. */
public interface AccountDirectory {
    /** 按账号标识读取可用的服务端身份。 */
    Actor account(long accountId);
    /** 判断指定空间是否处于启用状态。 */
    boolean workspaceEnabled(long workspaceId);

    /** 重新查询传入身份对应的当前账号。 */
    default Actor current(Actor supplied) {
        if (supplied == null || supplied.id() < 1) throw new ApplicationFault("42000", 401, "请先登录");
        return account(supplied.id());
    }
}
