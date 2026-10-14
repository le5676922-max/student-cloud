package im.tlgxy.cloud.module.user.manager;

import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.dto.UserStorageSourceDetailDTO;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.module.user.model.response.UserDetailResponse;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceStorageProvisioningService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 用户管理器
 * <p>
 * 提供用户相关的核心数据操作，直接与数据库交互，处理用户和用户存储源权限的增删改操作。
 * 与 UserService 不同，此类更侧重于底层数据操作和事务管理，包括：
 * 1. 保存用户信息及其关联的存储策略权限（事务操作）
 * 2. 删除用户及其关联的存储策略权限（事务操作）
 * 3. 组装用户详细信息（包含存储源权限列表）
 * 4. 新增存储源时自动为所有用户添加默认权限
 * </p>
 * <p>
 * 所有数据操作都使用 Spring 缓存注解管理缓存，确保数据一致性。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class UserManager {

    /**
     * 用户数据访问层，操作 user 表
     */
    @Resource
    private UserMapper userMapper;

    /**
     * 用户存储源权限数据访问层，操作 user_storage_source 关联表
     */
    @Resource
    private UserStorageSourceMapper userStorageSourceMapper;

    @Resource
    private WorkspaceStorageProvisioningService workspaceStorage;


    /**
     * 保存用户信息及用户关联的存储策略权限
     * <p>
     * 在一个事务中完成以下操作：
     * 1. 如果用户 ID 为空则新增用户，否则更新已有用户
     * 2. 删除该用户原有的所有存储策略权限
     * 3. 插入新的存储策略权限列表
     * 操作完成后会清除用户存储源权限缓存。
     * </p>
     *
     * @param user                  用户实体对象（新增时 ID 为 null）
     * @param userStorageSourceList 用户存储策略权限列表
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY, allEntries = true)
    })
    @Transactional(rollbackFor = Exception.class)
    public void saveUserInfo(User user, List<UserStorageSource> userStorageSourceList) {
        // 保存或新增用户
        Integer userId = user.getId();
        boolean newWorkspaceAdministrator = userId == null && user.getRole() == UserRoleEnum.TENANT_ADMIN;
        if (userId == null) {
            // 新增用户：插入后 MyBatis 会自动回填 ID
            userMapper.insert(user);
            userId = user.getId();
        } else {
            // 更新已有用户
            userMapper.updateById(user);
        }

        // 更新用户存储策略权限列表：先删后增，实现全量替换
        userStorageSourceMapper.deleteByUserId(userId);
        for (UserStorageSource userStorageSource : userStorageSourceList) {
            userStorageSource.setUserId(userId);
            userStorageSourceMapper.insert(userStorageSource);
        }
        if (newWorkspaceAdministrator) workspaceStorage.grantToNewWorkspaceAdministrator(user);
    }


    /**
     * 删除用户及关联的存储策略权限
     * <p>
     * 在一个事务中删除用户基本信息及其所有存储策略权限记录。
     * </p>
     *
     * @param userId 要删除的用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAllByUserId(Integer userId) {
        // 删除用户基本信息
        int deleteUserCount = userMapper.deleteById(userId);
        log.info("删除用户, userId: {}, deleteCount: {}", userId, deleteUserCount);

        // 删除用户关联的所有存储策略权限
        int deleteUserStorageSourceCount = userStorageSourceMapper.deleteByUserId(userId);
        log.info("删除用户存储策略权限, userId: {}, deleteCount: {}", userId, deleteUserStorageSourceCount);
    }


    /**
     * 组装用户详细信息
     * <p>
     * 根据用户实体对象，查询该用户关联的存储源权限列表，
     * 将基本信息和权限信息组装为用户详情响应对象。
     * </p>
     *
     * @param user 用户实体对象
     * @return 包含用户基本信息和存储源权限列表的用户详情响应对象
     */
    public UserDetailResponse assembleUserDetail(User user) {
        Integer userId = user.getId();
        // 获取用户与存储策略的关联关系
        List<UserStorageSourceDetailDTO> userStorageListByUserId = userStorageSourceMapper.getDTOListByUserId(userId);

        // 使用 Builder 模式构建用户详情对象
        return UserDetailResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .enable(user.getEnable())
                .createTime(user.getCreateTime())
                .userStorageSourceList(userStorageListByUserId)
                .defaultPermissions(user.getDefaultPermissions())
                .tenantId(user.getTenantId())
                .role(user.getRole())
                .build();
    }


    /**
     * 新增存储源时，自动按照各个用户的默认权限配置，为该存储源添加权限
     * <p>
     * 当系统中新增了一个存储源后，遍历所有用户，根据每个用户的默认权限集合
     * 为新存储源创建对应的权限记录。如果用户的默认权限集合不为空，则启用该权限；
     * 如果为空，则创建的权限记录为禁用状态。
     * </p>
     *
     * @param storageId 新创建的存储源 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void addDefaultPermissionsForTenantUsersInStorageSource(Integer storageId, Integer tenantId) {
        log.info("为存储源添加默认权限, storageId: {}", storageId);
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .and(query -> query.eq(User::getTenantId, tenantId)
                        .or().eq(User::getRole, UserRoleEnum.ADMIN))
                .ne(User::getId, UserConstant.ANONYMOUS_ID)
                .in(User::getRole, UserRoleEnum.ADMIN, UserRoleEnum.TENANT_ADMIN, UserRoleEnum.USER));
        for (User user : users) {
            if (UserConstant.ANONYMOUS_ID.equals(user.getId())
                    || user.getRole() == null || !user.getRole().isLoginRole()) {
                continue;
            }
            // 获取用户的默认权限集合
            Set<String> defaultPermissions = user.getDefaultPermissions();
            UserStorageSource userStorageSource = new UserStorageSource();
            userStorageSource.setUserId(user.getId());
            userStorageSource.setStorageSourceId(storageId);
            // 默认根路径
            userStorageSource.setRootPath(StringUtils.SLASH);
            userStorageSource.setPermissions(defaultPermissions);
            // 如果默认权限不为空则启用，否则禁用
            userStorageSource.setEnable(CollectionUtils.isNotEmpty(defaultPermissions));
            userStorageSourceMapper.insert(userStorageSource);
            log.info("为用户添加存储源的默认权限: username: {}, storageId: {}, defaultPermissions: {}", user.getUsername(), storageId, defaultPermissions);
        }
    }

}
