package im.tlgxy.cloud.module.user.service;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.event.UserCopyEvent;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.*;
import im.tlgxy.cloud.module.user.model.response.UserDetailResponse;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static im.tlgxy.cloud.module.user.service.UserService.USER_CACHE_KEY;

@Slf4j
@Service
@CacheConfig(cacheNames = USER_CACHE_KEY)
public class UserService {

    public static final String USER_CACHE_KEY = "user";

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserManager userManager;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    @Resource
    private TlgxyCloudCacheManager studentCloudCacheManager;

    @Resource
    private TenantSecurityService tenantSecurityService;

    @Resource
    private TenantService tenantService;

    @Resource
    private StorageSourceMapper storageSourceMapper;

    /**
     * 根据用户 ID 获取用户
     *
     * @param   id
     *          用户 ID
     *
     * @return  用户
     */
    @Cacheable(key = "#id", unless = "#result == null", condition = "#id != null")
    public User getById(Integer id) {
        return userMapper.selectById(id);
    }

    @Cacheable(key = "#username", unless = "#result == null", condition = "#username != null")
    public Integer getIdByUsername(String username) {
        return userMapper.findIdByUsername(username);
    }

    /**
     * 根据用户名获取用户
     *
     * @param   username
     *          用户名
     *
     * @return  用户
     */
    public User getByUsername(String username) {
        UserService userService = (UserService) AopContext.currentProxy();
        Integer userId = userService.getIdByUsername(username);
        if (userId == null) {
            return null;
        }
        return userService.getById(userId);
    }


    /**
     * 根据用户 ID 获取用户详细信息，包含用户的基本信息和用户与存储策略的关联关系.
     *
     * @param   userId
     *          用户 ID
     *
     * @return  用户详细信息
     */
    public UserDetailResponse getUserDetailById(Integer userId) {
        User user = tenantSecurityService.requireManageUser(userId);
        return userManager.assembleUserDetail(user);
    }


    /**
     * 根据查询条件查询用户列表
     *
     * @param   queryObj
     *          查询条件对象
     *
     * @return  用户列表
     */
    public List<UserDetailResponse> listUserDetail(QueryUserRequest queryObj) {
        List<UserDetailResponse> userDetailResponseList = new ArrayList<>();

        LambdaQueryWrapper<User> queryWrapper = new QueryWrapper<User>()
                .orderBy(true, queryObj.getSortAsc(), StringUtils.camelToUnderline(queryObj.getSortField()))
                .lambda()
                .like(ObjUtil.isNotEmpty(queryObj.getUsername()), User::getUsername, queryObj.getUsername())
                .like(ObjUtil.isNotEmpty(queryObj.getNickname()), User::getNickname, queryObj.getNickname())
                .eq(ObjUtil.isNotEmpty(queryObj.getEnable()), User::getEnable, queryObj.getEnable())
                .ge(ObjUtil.isNotEmpty(queryObj.getDateFrom()), User::getCreateTime, queryObj.getDateFrom())
                .le(ObjUtil.isNotEmpty(queryObj.getDateTo()), User::getCreateTime, queryObj.getDateTo());

        tenantSecurityService.requireSystemAdmin();
        // ID 0 is a permission template, not an additional account or role.
        queryWrapper.ne(User::getId, UserConstant.NEW_USER_TEMPLATE_ID);

        List<User> users = userMapper.selectList(queryWrapper);

        users.forEach(user -> userDetailResponseList.add(userManager.assembleUserDetail(user)));
        if (Boolean.TRUE.equals(queryObj.getHideDisabledStorage())) {
            userDetailResponseList.forEach(userDetailResponse -> {
                userDetailResponse.getUserStorageSourceList().removeIf(userStorageSourceDetailDTO -> !Boolean.TRUE.equals(userStorageSourceDetailDTO.getEnable()));
            });
        }
        return userDetailResponseList;
    }


    /**
     * 保存或更新用户
     *
     * @param   saveUserRequest
     *          用户信息
     *
     * @return  保存后的用户
     */
    @CacheEvict(allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public User saveOrUpdate(SaveUserRequest saveUserRequest) {
        tenantSecurityService.requireSystemAdmin();
        User existing = saveUserRequest.getId() == null
                ? null
                : tenantSecurityService.requireManageUser(saveUserRequest.getId());

        if (existing != null && Objects.equals(existing.getId(), UserConstant.ADMIN_ID)
                && (Boolean.FALSE.equals(saveUserRequest.getEnable())
                || (saveUserRequest.getRole() != null && saveUserRequest.getRole() != UserRoleEnum.ADMIN))) {
            throw new BizException("系统管理员不能被停用或降级");
        }
        if (existing == null && StringUtils.isBlank(saveUserRequest.getPassword())) {
            throw new BizException("新用户密码不能为空");
        }

        boolean userNameIsDuplicate = checkDuplicateUsername(saveUserRequest.getId(), saveUserRequest.getUsername());
        if (userNameIsDuplicate) {
            throw new BizException(ErrorCode.BIZ_USER_EXIST);
        }

        User user = new User();
        user.setId(saveUserRequest.getId());
        user.setUsername(saveUserRequest.getUsername());
        user.setNickname(saveUserRequest.getNickname());
        user.setEnable(saveUserRequest.getEnable());
        user.setDefaultPermissions(saveUserRequest.getDefaultPermissions());

        UserRoleEnum role = existing == null ? UserRoleEnum.USER : existing.getRole();
        if (saveUserRequest.getRole() != null && saveUserRequest.getRole() != role) {
            throw new BizException("用户角色不可变更：新账号只能为普通用户，系统管理员和访客为内置账号");
        }
        boolean nonLoginAccount = existing != null
                && (Objects.equals(existing.getId(), UserConstant.NEW_USER_TEMPLATE_ID)
                || role == UserRoleEnum.GUEST);
        if (nonLoginAccount && StringUtils.isNotBlank(saveUserRequest.getPassword())) {
            throw new BizException("访客和新用户权限模板不能设置登录密码");
        }
        Integer tenantId = role == UserRoleEnum.ADMIN ? null
                : nonLoginAccount ? existing.getTenantId()
                : Optional.ofNullable(saveUserRequest.getTenantId())
                        .orElse(existing == null ? UserConstant.DEFAULT_TENANT_ID : existing.getTenantId());
        if (role != UserRoleEnum.ADMIN) {
            tenantService.getEnabledById(tenantId);
        }
        if (nonLoginAccount) {
            user.setUsername(existing.getUsername());
        }
        user.setRole(role);
        user.setTenantId(tenantId);
        if (role == UserRoleEnum.ADMIN) {
            user.setDefaultPermissions(allFilePermissions());
        }

        if (StringUtils.isNotBlank(saveUserRequest.getPassword())) {
            passwordEncryptAndSet(saveUserRequest.getPassword(), user);
        }

        List<UserStorageSource> storageAccessList = sanitizeStorageAccess(
                saveUserRequest.getUserStorageSourceList(), tenantId, role == UserRoleEnum.ADMIN);
        userManager.saveUserInfo(user, storageAccessList);
        if (user.getId() != null) {
            studentCloudCacheManager.clearUserEnableStorageSourceCache(user.getId());
        }
        return user;
    }


    /**
     * 更新用户启用状态
     *
     * @param   id
     *          用户 ID
     *
     * @param   enable
     *          是否启用
     */
    @CacheEvict(key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void updateUserEnable(Integer id, boolean enable) {
        User target = tenantSecurityService.requireManageUser(id);
        if (target.getRole() == UserRoleEnum.ADMIN) {
            throw new BizException("系统管理员不能被停用");
        }
        User user = new User();
        user.setId(id);
        user.setEnable(enable);
        userMapper.updateById(user);
        studentCloudCacheManager.clearUserEnableStorageSourceCache(id);
    }


    /**
     * 初始化管理员用户名、密码、权限.
     *
     * @param   username
     *          用户名
     *
     * @param   password
     *          密码
     *
     * @return  是否更新成功
     */
    public boolean initAdminUser(String username, String password) {
        User user = userMapper.selectById(UserConstant.ADMIN_ID);
        if (user == null) {
            throw new BizException("系统异常，管理员用户不存在，请检测数据库或重建数据库。");
        }

        user.setUsername(username);
        passwordEncryptAndSet(password, user);

        // 管理员用户默认权限
        Set<String> defaultPermissions = new HashSet<>();
        for (FileOperatorTypeEnum value : FileOperatorTypeEnum.values()) {
            if (!StringUtils.startWith(value.getValue(), "ignore")) {
                defaultPermissions.add(value.getValue());
            }
        }
        user.setDefaultPermissions(defaultPermissions);
        user.setRole(UserRoleEnum.ADMIN);
        user.setTenantId(null);
        return userMapper.updateById(user) == 1;
    }


    /**
     * 修改当前用户的用户名和密码，需要校验旧密码是否正确.
     *
     * @param   updateUserPwdRequest
     *          修改密码请求对象
     */
    @CacheEvict(allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public void updateUserNameAndPwdById(Integer id, UpdateUserPwdRequest updateUserPwdRequest) {
        requireLoginUser(id);
        User user = userMapper.selectById(id);
        if (user == null || Objects.equals(id, UserConstant.ANONYMOUS_ID)) {
            throw new BizException(ErrorCode.BIZ_USER_NOT_EXIST);
        }

        // 验证旧密码是否正确，如果旧密码不为空，则进行验证
        if (StringUtils.isNotBlank(user.getPassword()) &&
                !PasswordVerifyUtils.verify(user.getPassword(), user.getSalt(), updateUserPwdRequest.getOldPassword())) {
            throw new BizException(ErrorCode.BIZ_OLD_PASSWORD_ERROR);
        }

        // 验证新密码和确认密码是否一致
        if (!updateUserPwdRequest.getNewPassword().equals(updateUserPwdRequest.getConfirmPassword())) {
            throw new BizException(ErrorCode.BIZ_PASSWORD_NOT_SAME);
        }

        passwordEncryptAndSet(updateUserPwdRequest.getNewPassword(), user);
        userMapper.updateById(user);
    }


    /**
     * 根据 ID 删除用户，无法删除内置的管理员和访客，删除是会自动删除用户与存储策略的关联关系.
     *
     * @param   id
     *          要删除调用用户 ID
     */
    @CacheEvict(allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Integer id) {
        User user = tenantSecurityService.requireManageUser(id);
        if (user.getId() <= UserConstant.ANONYMOUS_ID) {
            throw new BizException(ErrorCode.BIZ_DELETE_BUILT_IN_USER);
        }

        // 删除用户及关联的数据
        userManager.deleteAllByUserId(id);

        // 发布用户删除事件
        applicationEventPublisher.publishEvent(new UserDeleteEvent(user));
        studentCloudCacheManager.clearUserEnableStorageSourceCache(id);
    }


    /**
     * 根据用户 ID 判断是否是管理员，(管理员 ID 强制为 1)
     *
     * @param   id
     *          用户 ID
     *
     * @return  是否是管理员
     */
    public boolean isAdmin(Integer id) {
        User user = id == null ? null : userMapper.selectById(id);
        return user != null && Boolean.TRUE.equals(user.getEnable())
                && user.getRole() != null && user.getRole().isAdmin();
    }

    /** Guest and permission-template records never establish login sessions. */
    public User requireLoginUser(Integer id) {
        User user = requireActiveUser(id);
        if (Objects.equals(id, UserConstant.NEW_USER_TEMPLATE_ID)
                || Objects.equals(id, UserConstant.ANONYMOUS_ID)
                || (user.getRole() != UserRoleEnum.ADMIN && user.getRole() != UserRoleEnum.USER)) {
            throw new BizException("访客和新用户权限模板不能登录");
        }
        return user;
    }

    /** Check fresh account and workspace state, including already established sessions. */
    public User requireActiveUser(Integer id) {
        User user = id == null ? null : userMapper.selectById(id);
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException("当前账号不存在或已被停用");
        }
        if (user.getRole() != UserRoleEnum.ADMIN) {
            tenantService.getEnabledById(user.getTenantId());
        }
        return user;
    }


    /**
     * 根据用户名判断是否是管理员，(管理员 ID 强制为 1)
     *
     * @param   username
     *          用户名
     *
     * @return  是否是管理员
     */
    public boolean isAdmin(String username) {
        User user = ((UserService) AopContext.currentProxy()).getByUsername(username);
        return user != null && isAdmin(user.getId());
    }


    /**
     * 检查用户名是否重复
     *
     * @param   ignoreUserId
     *          忽略的用户 ID, 用于检查重复时, 排除自身.
     *
     * @param   username
     *          要检查的用户名
     *
     * @return  是否重复
     */
    public boolean checkDuplicateUsername(Integer ignoreUserId, String username) {
        return userMapper.countByUsername(username, ignoreUserId) > 0;
    }

    /**
     * 重置管理员用户的用户名、密码。
     *
     * @param   requestObj
     *          重置用户名和密码请求对象
     */
    @CacheEvict(allEntries = true)
    public void resetAdminLoginInfo(ResetAdminUserNameAndPasswordRequest requestObj) {
        User user = userMapper.selectById(UserConstant.ADMIN_ID);
        user.setUsername(requestObj.getUsername());
        user.setPassword(SecureUtil.md5(requestObj.getPassword()));
        passwordEncryptAndSet(requestObj.getPassword(), user);
        userMapper.updateById(user);

    }

    /**
     * 密码加盐并设置到用户对象中
     *
     * @param   originPassword
     *          原始密码
     *
     * @param   user
     *          用户对象
     */
    private static void passwordEncryptAndSet(String originPassword, User user) {
        Pair<String, String> encryptPair = PasswordVerifyUtils.encrypt(originPassword);
        user.setPassword(encryptPair.getFirst());
        user.setSalt(encryptPair.getSecond());
    }

    /** Internal SSO registration; never accepts a caller-selected source account or role. */
    @Transactional(rollbackFor = Exception.class)
    public Integer registerFromTemplate(String username) {
        User template = requireActiveUser(UserConstant.NEW_USER_TEMPLATE_ID);
        if (StringUtils.isBlank(username) || checkDuplicateUsername(null, username)) {
            throw new BizException("用户名为空或已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setNickname(username);
        user.setEnable(true);
        user.setRole(UserRoleEnum.USER);
        user.setTenantId(template.getTenantId());
        user.setDefaultPermissions(template.getDefaultPermissions());
        userMapper.insert(user);
        applicationEventPublisher.publishEvent(new UserCopyEvent(UserConstant.NEW_USER_TEMPLATE_ID, user.getId()));
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Integer copy(CopyUserRequest copyUserRequest) {
        // 检查目标用户名是否已存在
        String toUsername = copyUserRequest.getToUsername();
        boolean existUser = ((UserService)AopContext.currentProxy()).getByUsername(toUsername) != null;
        if (existUser) {
            throw new BizException(ErrorCode.BIZ_USER_EXIST);
        }

        // 检查复制源是否存在
        Integer fromUserId = copyUserRequest.getFromId();
        User user = tenantSecurityService.requireManageUser(fromUserId);

        if (user.getId() <= UserConstant.ANONYMOUS_ID || user.getRole() == UserRoleEnum.ADMIN) {
            throw new BizException("内置账号不能被复制");
        }

        User newUser = new User();
        BeanUtils.copyProperties(user, newUser);
        newUser.setId(null);
        newUser.setCreateTime(null);
        newUser.setUsername(null);
        newUser.setUsername(copyUserRequest.getToUsername());
        newUser.setNickname(copyUserRequest.getToNickname());
        if (StringUtils.isNotEmpty(copyUserRequest.getToPassword())) {
            passwordEncryptAndSet(copyUserRequest.getToPassword(), newUser);
        }
        userMapper.insert(newUser);

        Integer newUserId = newUser.getId();
        log.info("复制用户成功，源 [id: {}, username: {}, nickname: {}], 复制后 [id: {}, username: {}, nickname: {}]",
                fromUserId, user.getUsername(), user.getNickname(),
                newUserId, newUser.getUsername(), user.getNickname());

        UserCopyEvent userCopyEvent = new UserCopyEvent(fromUserId, newUserId);
        applicationEventPublisher.publishEvent(userCopyEvent);
        return newUserId;
    }

    private List<UserStorageSource> sanitizeStorageAccess(List<UserStorageSource> requested, Integer tenantId, boolean allTenants) {
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<>();
        }
        List<UserStorageSource> result = new ArrayList<>();
        for (UserStorageSource access : requested) {
            StorageSource storage = storageSourceMapper.selectById(access.getStorageSourceId());
            if (storage == null || (!allTenants && !Objects.equals(storage.getTenantId(), tenantId))) {
                throw new BizException("用户不能被授权使用其他工作空间的存储源");
            }
            access.setUserId(null);
            result.add(access);
        }
        return result;
    }

    private Set<String> allFilePermissions() {
        Set<String> permissions = new HashSet<>();
        for (FileOperatorTypeEnum value : FileOperatorTypeEnum.values()) {
            if (!StringUtils.startWith(value.getValue(), "ignore")) {
                permissions.add(value.getValue());
            }
        }
        return permissions;
    }

}
