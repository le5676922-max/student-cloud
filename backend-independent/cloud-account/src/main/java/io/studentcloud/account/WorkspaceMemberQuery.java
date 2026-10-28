package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded workspace member read model: profile and enrollment enrichment costs one query per page. */
public final class WorkspaceMemberQuery {
    private final JdbcTemplate jdbc;
    private final AccountAdministration accounts;

    public WorkspaceMemberQuery(JdbcTemplate jdbc, AccountAdministration accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public record Member(long id, String accountId, String username, String nickname, String email, boolean enabled,
                         Role role, Long tenantId, String membershipStatus) { }
    public record Page(List<Member> items, long total, int page) {
        public Page { items = List.copyOf(items); }
    }

    public Page page(Actor caller, int page, int limit, String search) {
        if (page < 1 || page > 10_000 || limit < 1 || limit > 100 || (long) (page - 1) * limit > 100_000)
            throw new ApplicationFault("41000",400,"分页参数无效");
        // These operations independently recheck the current account, role and workspace state.
        List<AccountView> rows = accounts.listWorkspaceMembers(caller,(page-1)*limit,limit,search);
        long total = accounts.countWorkspaceMembers(caller,search);
        if (rows.isEmpty()) return new Page(List.of(),total,page);
        Long workspace = caller.workspaceId();
        if (workspace == null || rows.size()>100) throw new ApplicationFault("40300",403,"没有本空间成员访问权限");
        Map<Long,Extra> extras = enrich(workspace,rows);
        List<Member> members = new ArrayList<>(rows.size());
        for (AccountView row : rows) {
            Extra extra = extras.get(row.id());
            // A member moved/removed between reads must never be enriched from a different workspace.
            if (extra == null || row.role()!=Role.USER || !Objects.equals(workspace,row.workspaceId())) continue;
            String nickname = extra.nickname()==null || extra.nickname().isBlank() ? row.username() : extra.nickname();
            String membership = extra.status()==null || "APPROVED".equals(extra.status()) ? "ACTIVE" : extra.status();
            members.add(new Member(row.id(),row.accountId(),row.username(),nickname,row.email(),row.enabled(),row.role(),row.workspaceId(),membership));
        }
        return new Page(members,total,page);
    }

    private Map<Long,Extra> enrich(long workspace,List<AccountView> rows) {
        List<Object> parameters = new ArrayList<>(rows.size()+1);
        parameters.add(workspace);
        rows.forEach(row->parameters.add(row.id()));
        String placeholders = String.join(",",Collections.nCopies(rows.size(),"?"));
        List<Extra> result = jdbc.query("SELECT a.id,p.nickname,r.status FROM sc_account a "
                        + "LEFT JOIN sc_public_profile p ON p.account_id=a.id "
                        + "LEFT JOIN sc_workspace_join_request r ON r.account_id=a.id AND r.workspace_id=a.workspace_id "
                        + "WHERE a.workspace_id=? AND a.role='USER' AND a.id IN ("+placeholders+")",
                (r,index)->new Extra(r.getLong("id"),r.getString("nickname"),r.getString("status")),parameters.toArray());
        Map<Long,Extra> byId = new HashMap<>();
        for (Extra extra:result) byId.put(extra.id(),extra);
        return byId;
    }

    private record Extra(long id,String nickname,String status) { }
}
