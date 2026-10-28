package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

final class AssistantAccess {
    private AssistantAccess() { }

    static Actor current(AccountDirectory accounts, Actor actor) {
        return accounts.current(actor);
    }

    static Actor administrator(AccountDirectory accounts, Actor actor) {
        Actor current = current(accounts, actor);
        if (current.role() != Role.ADMIN) throw fault("40300", 403, "只有全局管理员可以执行此操作");
        return current;
    }

    static void ownerLock(JdbcTemplate jdbc, long accountId) {
        // This guard belongs only to the intelligence database; no identity table is replicated or locked.
        jdbc.update("INSERT INTO sc_assistant_owner_guard(account_id) VALUES(?) ON DUPLICATE KEY UPDATE account_id=account_id", accountId);
        jdbc.queryForObject("SELECT account_id FROM sc_assistant_owner_guard WHERE account_id=? FOR UPDATE", Long.class, accountId);
    }

    static void page(int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 50) throw fault("41000", 400, "分页参数无效");
    }

    static String text(String content, int maximum, boolean optional) {
        String value = content == null && optional ? "" : content == null ? null : content.trim();
        if (value == null || !optional && value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw fault("41000", 400, "文本为空、过长或包含不支持的控制字符");
        return value;
    }

    static long insertId(JdbcTemplate jdbc, String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey(), "Generated identity is missing").longValue();
    }

    static ApplicationFault fault(String code, int status, String message) { return new ApplicationFault(code, status, message); }
}
