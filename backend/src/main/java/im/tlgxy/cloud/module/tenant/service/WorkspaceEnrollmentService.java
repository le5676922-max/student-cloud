package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceInvitation;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.model.request.CreateWorkspaceInvitationRequest;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceEnrollmentService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TenantSecurityService security;
    private final TenantMapper tenants;
    private final UserMapper users;
    private final UserStorageSourceMapper grants;
    private final StorageSourceMapper storages;
    private final UserManager userManager;
    private final WorkspaceInvitationMapper invitations;
    private final WorkspaceJoinRequestMapper joinRequests;
    private final WorkspaceStorageProvisioningService workspaceStorage;

    public record Enrollment(Tenant tenant, String status) { }
    public record InvitationInfo(Integer id, Integer tenantId, Instant createdAt, Instant expiresAt,
                                 Integer maxUses, Integer useCount, Boolean revoked) {
        static InvitationInfo from(WorkspaceInvitation value) {
            return new InvitationInfo(value.getId(), value.getTenantId(), Instant.ofEpochMilli(value.getCreatedAt()),
                    Instant.ofEpochMilli(value.getExpiresAt()), value.getMaxUses(), value.getUseCount(), value.getRevoked());
        }
    }
    public record CreatedInvitation(Integer id, String code, Integer tenantId, Instant createdAt,
                                    Instant expiresAt, Integer maxUses, Integer useCount, Boolean revoked) { }
    public record JoinRequestInfo(Integer id, Integer userId, String accountId, String email, String nickname,
                                  Integer tenantId, String status, Instant createdAt, Instant reviewedAt) { }
    public record ReviewResult(Integer id, String status, Integer userId) { }

    @Transactional(rollbackFor = Exception.class)
    public Enrollment createWorkspace(User user, String workspaceName) {
        String name = requireName(workspaceName);
        if (tenants.selectCount(Wrappers.<Tenant>lambdaQuery().eq(Tenant::getName, name)) > 0)
            throw new BizException("工作空间名称已存在");
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setEnable(true);
        tenants.insert(tenant);
        user.setTenantId(tenant.getId());
        user.setRole(UserRoleEnum.TENANT_ADMIN);
        user.setEnable(true);
        user.setDefaultPermissions(Arrays.stream(FileOperatorTypeEnum.values()).map(FileOperatorTypeEnum::getValue)
                .filter(value -> !value.startsWith("ignore")).collect(Collectors.toSet()));
        userManager.saveUserInfo(user, List.of());
        tenant.setOwnerUserId(user.getId());
        tenants.updateById(tenant);
        workspaceStorage.provisionNewWorkspace(tenant.getId());
        return new Enrollment(tenant, "ACTIVE");
    }

    @Transactional(rollbackFor = Exception.class)
    public Enrollment joinWorkspace(User user, String joinType, String joinValue) {
        String value = requireName(joinValue);
        if ("INVITE".equals(joinType)) return joinInvitation(user, value);
        if (!"NAME".equals(joinType)) throw new BizException("请选择邀请代码或工作空间名称加入");
        Tenant tenant = tenants.selectOne(Wrappers.<Tenant>lambdaQuery().eq(Tenant::getName, value));
        if (tenant == null || !value.equals(tenant.getName())) throw new BizException("工作空间不存在或已停用");
        tenant = lockTenant(tenant.getId());
        user.setRole(UserRoleEnum.USER);
        user.setTenantId(tenant.getId());
        user.setEnable(false);
        user.setDefaultPermissions(Set.of());
        userManager.saveUserInfo(user, List.of());
        WorkspaceJoinRequest application = new WorkspaceJoinRequest();
        application.setTenantId(tenant.getId());
        application.setUserId(user.getId());
        application.setStatus("PENDING");
        application.setCreatedAt(System.currentTimeMillis());
        joinRequests.insert(application);
        return new Enrollment(tenant, "PENDING");
    }

    private Enrollment joinInvitation(User user, String code) {
        if (!code.matches("WI_[A-Za-z0-9_-]{43}")) throw invalidInvitation();
        WorkspaceInvitation invitation = invitations.selectOne(Wrappers.<WorkspaceInvitation>lambdaQuery()
                .eq(WorkspaceInvitation::getTokenHash, hashCode(code)));
        if (invitation == null) throw invalidInvitation();
        Tenant tenant = lockTenant(invitation.getTenantId());
        if (invitations.lockEnabledIssuer(invitation.getIssuerUserId(), tenant.getId()) != 1) throw invalidInvitation();
        User issuer = users.selectById(invitation.getIssuerUserId());
        if (issuer == null || issuer.getRole() != UserRoleEnum.TENANT_ADMIN
                || !Boolean.TRUE.equals(issuer.getEnable()) || !Objects.equals(issuer.getTenantId(), tenant.getId()))
            throw invalidInvitation();
        if (invitations.consume(invitation.getId(), tenant.getId(), System.currentTimeMillis()) != 1)
            throw invalidInvitation();
        user.setRole(UserRoleEnum.USER);
        user.setTenantId(tenant.getId());
        user.setEnable(true);
        inheritPermissions(user, issuer, tenant.getId());
        return new Enrollment(tenant, "ACTIVE");
    }

    public List<InvitationInfo> invitations() {
        int tenantId = managedTenant().getId();
        return invitations.selectList(Wrappers.<WorkspaceInvitation>lambdaQuery()
                .eq(WorkspaceInvitation::getTenantId, tenantId).orderByDesc(WorkspaceInvitation::getId)
                .last("LIMIT 100")).stream().map(InvitationInfo::from).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CreatedInvitation createInvitation(CreateWorkspaceInvitationRequest request) {
        int tenantId = security.currentManagedTenantId();
        lockTenant(tenantId);
        int days = request.expiresInDays() == null ? 7 : request.expiresInDays();
        int maxUses = request.maxUses() == null ? 1 : request.maxUses();
        if (days < 1 || days > 30 || maxUses < 1 || maxUses > 100) throw new BizException("邀请有效期或使用次数不正确");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = "WI_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        WorkspaceInvitation value = new WorkspaceInvitation();
        value.setTenantId(tenantId);
        value.setIssuerUserId(security.currentUser().getId());
        value.setTokenHash(hashCode(code));
        value.setCreatedAt(now);
        value.setExpiresAt(now + days * 86_400_000L);
        value.setMaxUses(maxUses);
        value.setUseCount(0);
        value.setRevoked(false);
        invitations.insert(value);
        return new CreatedInvitation(value.getId(), code, tenantId, Instant.ofEpochMilli(now),
                Instant.ofEpochMilli(value.getExpiresAt()), maxUses, 0, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeInvitation(int id) {
        int tenantId = security.currentManagedTenantId();
        lockTenant(tenantId);
        WorkspaceInvitation invitation = invitations.selectById(id);
        if (invitation == null || !Objects.equals(invitation.getTenantId(), tenantId)) throw new BizException("邀请不存在");
        invitations.revoke(id, tenantId);
    }

    public List<JoinRequestInfo> joinRequests() {
        int tenantId = managedTenant().getId();
        return joinRequests.selectList(Wrappers.<WorkspaceJoinRequest>lambdaQuery()
                .eq(WorkspaceJoinRequest::getTenantId, tenantId).eq(WorkspaceJoinRequest::getStatus, "PENDING")
                .orderByAsc(WorkspaceJoinRequest::getId).last("LIMIT 100")).stream().map(value -> {
            User user = users.selectById(value.getUserId());
            if (user == null || !Objects.equals(user.getTenantId(), tenantId)) return null;
            return new JoinRequestInfo(value.getId(), user.getId(), user.getAccountId(), user.getEmail(), user.getNickname(),
                    tenantId, value.getStatus(), Instant.ofEpochMilli(value.getCreatedAt()),
                    value.getReviewedAt() == null ? null : Instant.ofEpochMilli(value.getReviewedAt()));
        }).filter(Objects::nonNull).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = UserService.USER_CACHE_KEY, allEntries = true)
    public ReviewResult review(int id, boolean approve) {
        int tenantId = security.currentManagedTenantId();
        lockTenant(tenantId);
        WorkspaceJoinRequest application = joinRequests.selectById(id);
        if (application == null || !Objects.equals(application.getTenantId(), tenantId)) throw new BizException("加入申请不存在");
        User member = users.selectById(application.getUserId());
        if (member == null || member.getRole() != UserRoleEnum.USER || !Objects.equals(member.getTenantId(), tenantId))
            throw new BizException("申请账号不存在或归属已改变");
        User reviewer = security.currentUser();
        String status = approve ? "APPROVED" : "REJECTED";
        if (joinRequests.reviewPending(id, tenantId, status, System.currentTimeMillis(), reviewer.getId()) != 1)
            throw new BizException("此申请已处理，请刷新列表");
        member.setEnable(approve);
        if (approve) inheritPermissions(member, reviewer, tenantId);
        else {
            member.setDefaultPermissions(Set.of());
            userManager.saveUserInfo(member, List.of());
        }
        return new ReviewResult(id, status, member.getId());
    }

    private void inheritPermissions(User member, User operator, int tenantId) {
        if (operator.getRole() != UserRoleEnum.TENANT_ADMIN || !Objects.equals(operator.getTenantId(), tenantId))
            throw new BizException("管理员与工作空间不匹配");
        member.setDefaultPermissions(operator.getDefaultPermissions() == null ? Set.of() : Set.copyOf(operator.getDefaultPermissions()));
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
        userManager.saveUserInfo(member, inherited);
    }

    private Tenant managedTenant() {
        int id = security.currentManagedTenantId();
        Tenant tenant = tenants.selectById(id);
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) throw new BizException("工作空间不存在或已停用");
        return tenant;
    }

    private Tenant lockTenant(int tenantId) {
        if (invitations.lockEnabledTenant(tenantId) != 1) throw new BizException("工作空间不存在或已停用");
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) throw new BizException("工作空间不存在或已停用");
        return tenant;
    }

    public static String requireName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 80 || normalized.chars().anyMatch(Character::isISOControl))
            throw new BizException("工作空间名称或邀请代码不能为空且不能超过80个字符");
        return normalized;
    }

    private static BizException invalidInvitation() { return new BizException("邀请代码无效、已过期、已撤销或已用完"); }

    static String hashCode(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
