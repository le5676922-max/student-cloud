package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.model.request.CreateWorkspaceMemberRequest;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** A deliberately narrow administration surface; global configuration remains ADMIN-only. */
@Service
@RequiredArgsConstructor
public class WorkspaceAdminService {
    private final TenantSecurityService security;
    private final TenantMapper tenants;
    private final UserMapper users;
    private final StorageSourceMapper storages;
    private final UserStorageSourceMapper grants;
    private final UserManager userManager;
    private final WorkspaceJoinRequestMapper joinRequests;

    public record Member(Integer id, String username, String accountId, String email, String nickname, boolean enabled,
                         String membershipStatus) {
        static Member from(User user) {
            return from(user, "ACTIVE");
        }

        static Member from(User user, String membershipStatus) {
            return new Member(user.getId(), user.getUsername(), user.getAccountId(), user.getEmail(), user.getNickname(),
                    Boolean.TRUE.equals(user.getEnable()), membershipStatus);
        }
    }
    public record MemberPage(long total, int page, List<Member> items) { }
    public record Summary(Integer tenantId, String tenantName, long memberCount, long enabledMemberCount, long storageCount) { }

    public Summary summary() {
        int tenantId = tenantId();
        Tenant tenant = tenants.selectById(tenantId);
        var members = memberQuery(tenantId);
        return new Summary(tenantId, tenant.getName(), users.selectCount(members),
                users.selectCount(memberQuery(tenantId).eq(User::getEnable, true)),
                storages.selectCount(new LambdaQueryWrapper<StorageSource>().eq(StorageSource::getTenantId, tenantId)));
    }

    public MemberPage members(int page, String search) {
        int tenantId = tenantId();
        if (page < 1 || page > 10_000 || (search != null && search.length() > 100)) throw new BizException("查询参数不正确");
        String query = search == null ? "" : search.trim();
        var countQuery = memberQuery(tenantId);
        if (!query.isEmpty()) countQuery.and(w -> w.like(User::getUsername, query).or().like(User::getNickname, query)
                .or().like(User::getAccountId, query).or().like(User::getEmail, query));
        long total = users.selectCount(countQuery);
        var rows = memberQuery(tenantId);
        if (!query.isEmpty()) rows.and(w -> w.like(User::getUsername, query).or().like(User::getNickname, query)
                .or().like(User::getAccountId, query).or().like(User::getEmail, query));
        rows.orderByDesc(User::getId).last("LIMIT 20 OFFSET " + ((page - 1) * 20));
        List<User> pageMembers = users.selectList(rows);
        Map<Integer, String> statuses = membershipStatuses(tenantId, pageMembers);
        return new MemberPage(total, page, pageMembers.stream()
                .map(user -> Member.from(user, statuses.getOrDefault(user.getId(), "ACTIVE"))).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public Member create(CreateWorkspaceMemberRequest request) {
        int tenantId = tenantId();
        String username = request.username().trim();
        if (!username.matches("[\\p{L}\\p{N}_.-]{3,50}") || UserService.isReservedManualUsername(username)
                || users.selectCount(new LambdaQueryWrapper<User>().and(aliases -> aliases
                        .eq(User::getUsername, username).or().eq(User::getAccountId, username)
                        .or().eq(User::getEmail, username.toLowerCase(java.util.Locale.ROOT)))) > 0)
            throw new BizException("用户名不合法或已存在");
        if (request.password().isBlank()) throw new BizException("密码不能为空");
        User operator = security.currentUser();
        User member = new User();
        member.setUsername(username);
        member.setNickname(request.nickname() == null || request.nickname().isBlank() ? username : request.nickname().trim());
        member.setEnable(true);
        member.setRole(UserRoleEnum.USER); // Never accept a caller-selected role.
        member.setTenantId(tenantId);
        member.setDefaultPermissions(operator.getDefaultPermissions() == null ? Set.of() : Set.copyOf(operator.getDefaultPermissions()));
        var password = PasswordVerifyUtils.encrypt(request.password());
        member.setPassword(password.getFirst());
        member.setSalt(password.getSecond());
        List<UserStorageSource> inherited = new ArrayList<>();
        for (UserStorageSource grant : grants.selectByUserId(operator.getId())) {
            StorageSource source = storages.selectById(grant.getStorageSourceId());
            if (source == null || !Objects.equals(source.getTenantId(), tenantId)) continue;
            UserStorageSource copy = new UserStorageSource();
            copy.setStorageSourceId(source.getId());
            copy.setRootPath(grant.getRootPath());
            copy.setEnable(grant.getEnable());
            copy.setPermissions(grant.getPermissions() == null ? Set.of() : Set.copyOf(grant.getPermissions()));
            inherited.add(copy);
        }
        try {
            userManager.saveUserInfo(member, inherited);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.BIZ_USER_EXIST);
        }
        return Member.from(member);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = UserService.USER_CACHE_KEY, key = "#userId")
    public Member setEnabled(int userId, boolean enabled) {
        int tenantId = tenantId();
        User target = users.selectById(userId);
        if (target == null || target.getRole() != UserRoleEnum.USER || !Objects.equals(target.getTenantId(), tenantId))
            throw new BizException("只能管理本工作空间的普通用户");
        if (enabled && joinRequests.selectCount(new LambdaQueryWrapper<WorkspaceJoinRequest>()
                .eq(WorkspaceJoinRequest::getUserId, userId).ne(WorkspaceJoinRequest::getStatus, "APPROVED")) > 0)
            throw new BizException("请先在加入申请中审核通过此成员");
        User update = new User();
        update.setId(userId);
        update.setEnable(enabled);
        users.updateById(update);
        target.setEnable(enabled);
        return Member.from(target, membershipStatuses(tenantId, List.of(target)).getOrDefault(userId, "ACTIVE"));
    }

    private Map<Integer, String> membershipStatuses(int tenantId, List<User> members) {
        if (members.isEmpty()) return Map.of();
        return joinRequests.selectList(new LambdaQueryWrapper<WorkspaceJoinRequest>()
                        .eq(WorkspaceJoinRequest::getTenantId, tenantId)
                        .in(WorkspaceJoinRequest::getUserId, members.stream().map(User::getId).toList())
                        .select(WorkspaceJoinRequest::getUserId, WorkspaceJoinRequest::getStatus))
                .stream().collect(Collectors.toMap(WorkspaceJoinRequest::getUserId, WorkspaceJoinRequest::getStatus));
    }

    private int tenantId() {
        int id = security.currentManagedTenantId();
        Tenant tenant = tenants.selectById(id);
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) throw new BizException("工作空间不存在或已停用");
        return id;
    }

    private LambdaQueryWrapper<User> memberQuery(int tenantId) {
        return new LambdaQueryWrapper<User>().eq(User::getTenantId, tenantId).eq(User::getRole, UserRoleEnum.USER);
    }
}
