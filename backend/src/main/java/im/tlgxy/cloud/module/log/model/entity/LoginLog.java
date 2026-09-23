package im.tlgxy.cloud.module.log.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 登录日志实体类
 * <p>
 * 对应数据库表 login_log，记录用户的每一次登录尝试。
 * 包括登录用户名、密码（用于记录失败的尝试）、登录时间、IP、User-Agent、Referer 以及登录结果。
 * </p>
 *
 * @author zhaojun
 */
@Data
@TableName(value = "login_log")
public class LoginLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /** 登录用户名 */
    @TableField(value = "username")
    private String username;

    /** 登录密码（记录用户输入的密码，用于审计） */
    @TableField(value = "`password`")
    private String password;

    /** 登录时间，插入时自动填充 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    /** 登录者的 IP 地址 */
    @TableField(value = "ip")
    private String ip;

    /** 登录者的 User-Agent 信息 */
    @TableField(value = "user_agent")
    private String userAgent;

    /** 登录来源页面的 Referer 信息 */
    @TableField(value = "referer")
    private String referer;

    /** 登录结果（成功或失败的原因） */
    @TableField(value = "`result`")
    private String result;

}