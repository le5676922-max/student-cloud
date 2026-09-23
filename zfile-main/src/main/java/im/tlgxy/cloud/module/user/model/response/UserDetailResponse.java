package im.tlgxy.cloud.module.user.model.response;

import im.tlgxy.cloud.module.user.model.dto.UserStorageSourceDetailDTO;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 用户详情响应类
 * <p>
 * 返回用户的详细信息，包括基本信息、权限、存储源分配及账号状态。
 * 使用 Lombok 的 @Builder 注解支持建造者模式构建对象，
 * 常见于用户管理页面中查看或编辑单个用户时返回。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Builder
public class UserDetailResponse {

    /**
     * 用户唯一标识 ID
     */
    private Integer id;

    /**
     * 用户名（登录账号）
     */
    private String username;

    /**
     * 用户昵称（显示名称）
     */
    private String nickname;

    /**
     * 默认权限集合
     * <p>用户拥有的一组默认操作权限，如读取、上传、删除等。</p>
     */
    private Set<String> defaultPermissions;

    /**
     * 用户关联的存储源详情列表
     * <p>每个条目包含该用户可访问的存储源及其对应权限信息。</p>
     */
    private List<UserStorageSourceDetailDTO> userStorageSourceList;

    /**
     * 账号启用状态
     * <p>true 表示账号已启用，false 表示账号已禁用。</p>
     */
    private Boolean enable;

    /**
     * 账号创建时间
     */
    private Date createTime;

    private Integer tenantId;

    private UserRoleEnum role;

}
