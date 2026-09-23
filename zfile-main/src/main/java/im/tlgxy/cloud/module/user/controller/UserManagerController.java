package im.tlgxy.cloud.module.user.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.CheckUserDuplicateRequest;
import im.tlgxy.cloud.module.user.model.request.CopyUserRequest;
import im.tlgxy.cloud.module.user.model.request.QueryUserRequest;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import im.tlgxy.cloud.module.user.model.response.UserDetailResponse;
import im.tlgxy.cloud.module.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;

/**
 * 用户管理控制器
 * <p>
 * 提供管理员对系统用户的完整 CRUD（增删改查）管理接口，包括：
 * 1. 用户列表查询（支持条件筛选）
 * 2. 新增用户和更新用户信息
 * 3. 删除用户
 * 4. 启用/禁用用户
 * 5. 获取单个用户详细信息
 * 6. 检查用户名是否重复
 * 7. 复制用户（连同其存储源配置一并复制）
 * </p>
 * <p>
 * 此类中的写操作接口均标注了 @DemoDisable 注解，在演示模式下不可用。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "用户管理")
@ApiSort(6)
@RestController
@RequestMapping("/admin")
public class UserManagerController {

    /**
     * 用户服务，处理用户管理的核心业务逻辑
     */
    @Resource
    private UserService userService;

    /**
     * 获取用户列表
     * <p>
     * 根据查询条件筛选用户，返回每个用户的详细信息列表。
     * 查询条件支持用户名等字段的模糊匹配。
     * </p>
     *
     * @param queryObj 查询条件对象，包含用户名等筛选参数
     * @return 用户详情响应对象列表
     */
    @ApiOperationSupport(order = 1)
    @GetMapping("/user/list")
    @Operation(summary = "用户列表")
    @ResponseBody
    public AjaxJson<Collection<UserDetailResponse>> list(QueryUserRequest queryObj) {
        List<UserDetailResponse> userList = userService.listUserDetail(queryObj);
        return AjaxJson.getSuccessData(userList);
    }

    /**
     * 新增或更新用户
     * <p>
     * 如果请求中包含用户 ID 且该用户已存在，则执行更新操作；
     * 否则创建新用户。同时处理用户关联的存储源配置。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param saveUserRequest 包含用户信息及存储源配置的请求对象
     * @return 保存后的用户实体对象
     */
    @ApiOperationSupport(order = 2)
    @PostMapping("/user/saveOrUpdate")
    @Operation(summary = "添加用户")
    @ResponseBody
    @DemoDisable
    public AjaxJson<User> saveOrUpdate(@RequestBody SaveUserRequest saveUserRequest) {
        return AjaxJson.getSuccessData(userService.saveOrUpdate(saveUserRequest));
    }

    /**
     * 删除用户
     * <p>
     * 根据用户 ID 删除指定用户。删除操作会触发用户删除事件，
     * 以便其他模块进行相应的清理工作。演示模式下禁用此接口。
     * </p>
     *
     * @param id 要删除的用户 ID
     * @return 被删除的用户 ID
     */
    @ApiOperationSupport(order = 3)
    @DeleteMapping("/user/delete/{id}")
    @Operation(summary = "删除用户")
    @ResponseBody
    @DemoDisable
    public AjaxJson<Integer> delete(@PathVariable("id") Integer id) {
        userService.deleteById(id);
        return AjaxJson.getSuccessData(id);
    }

    /**
     * 启用用户
     * <p>
     * 将指定用户的启用状态设置为启用（true），被禁用的用户将无法登录系统。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param id 要启用的用户 ID
     * @return 被启用的用户 ID
     */
    @ApiOperationSupport(order = 5)
    @PostMapping("/user/enable/{id}")
    @Operation(summary = "启用用户")
    @ResponseBody
    @DemoDisable
    public AjaxJson<Integer> enable(@PathVariable Integer id) {
        userService.updateUserEnable(id, true);
        return AjaxJson.getSuccessData(id);
    }

    /**
     * 禁用用户
     * <p>
     * 将指定用户的启用状态设置为禁用（false），被禁用的用户将无法登录系统。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param id 要禁用的用户 ID
     * @return 被禁用的用户 ID
     */
    @ApiOperationSupport(order = 6)
    @PostMapping("/user/disable/{id}")
    @Operation(summary = "禁用用户")
    @ResponseBody
    @DemoDisable
    public AjaxJson<Integer> disable(@PathVariable Integer id) {
        userService.updateUserEnable(id, false);
        return AjaxJson.getSuccessData(id);
    }

    /**
     * 获取单个用户信息
     * <p>
     * 根据用户 ID 获取用户的详细信息，包括基本信息和关联的存储源权限配置。
     * </p>
     *
     * @param id 用户 ID
     * @return 用户详情响应对象
     */
    @ApiOperationSupport(order = 7)
    @GetMapping("/user/{id}")
    @Operation(summary = "获取用户信息")
    @ResponseBody
    public AjaxJson<UserDetailResponse> getUser(@PathVariable("id") Integer id) {
        UserDetailResponse user = userService.getUserDetailById(id);
        return AjaxJson.getSuccessData(user);
    }

    /**
     * 检查用户名是否重复
     * <p>
     * 在新增或修改用户时，校验用户名是否已存在。
     * 如果提供了用户 ID（编辑场景），则排除该 ID 对应的用户进行查重。
     * </p>
     *
     * @param checkUserDuplicateRequest 包含用户 ID（可选）和用户名的请求对象
     * @return true 表示用户名重复，false 表示不重复
     */
    @ApiOperationSupport(order = 8)
    @GetMapping("/user/checkDuplicate")
    @Operation(summary = "检查用户名是否重复")
    @ResponseBody
    public AjaxJson<Boolean> checkDuplicate(CheckUserDuplicateRequest checkUserDuplicateRequest) {
        Integer id = checkUserDuplicateRequest.getId();
        String username = checkUserDuplicateRequest.getUsername();
        return AjaxJson.getSuccessData(userService.checkDuplicateUsername(id, username));
    }

    /**
     * 复制用户
     * <p>
     * 根据指定用户的配置，创建一个新的用户副本，包括该用户的存储源权限配置。
     * 复制后的用户使用新的用户名和密码。演示模式下禁用此接口。
     * </p>
     *
     * @param copyUserRequest 包含源用户 ID、新用户名和新密码的请求对象
     * @return 新创建用户的 ID
     */
    @ApiOperationSupport(order = 9)
    @Operation(summary = "复制用户", description ="复制用户配置")
    @PostMapping("/user/copy")
    @DemoDisable
    public AjaxJson<Integer> copyStorage(@RequestBody @Valid CopyUserRequest copyUserRequest) {
        Integer id = userService.copy(copyUserRequest);
        return AjaxJson.getSuccessData(id);
    }

}