package im.tlgxy.cloud.module.storage.model.result;

import lombok.Data;

/**
 * 115 网盘开放平台授权状态查询结果类
 * <p>
 * 封装 115 网盘开放平台 OAuth 设备码授权流程中，
 * 轮询查询授权状态的响应结果。
 * 包含授权状态（等待扫码、已扫码、授权成功或失败）、
 * 提示消息以及授权成功后的访问令牌和刷新令牌。
 * 提供静态工厂方法用于快速创建不同状态的响应对象。
 * </p>
 */
@Data
public class Open115GetStatusResult {

    /**
     * 授权状态：
     * waiting - 等待用户扫码，
     * scanning - 用户已扫码，等待确认，
     * success - 授权成功，
     * error - 授权失败
     */
    private String status;

    /**
     * 状态提示消息
     */
    private String msg;

    /**
     * 访问令牌（accessToken），授权成功后返回
     */
    private String accessToken;

    /**
     * 刷新令牌（refreshToken），授权成功后返回，用于刷新访问令牌
     */
    private String refreshToken;

    /**
     * 访问令牌过期时间（Unix 时间戳），授权成功后返回
     */
    private Integer expiredAt;

    /**
     * 创建授权失败的响应对象
     *
     * @param msg 错误消息
     * @return 状态为 error 的结果对象
     */
    public static Open115GetStatusResult error(String msg) {
        Open115GetStatusResult open115GetStatusResult = new Open115GetStatusResult();
        open115GetStatusResult.setStatus("error");
        open115GetStatusResult.setMsg(msg);
        return open115GetStatusResult;
    }

    /**
     * 创建等待用户扫码的响应对象
     *
     * @return 状态为 waiting 的结果对象
     */
    public static Open115GetStatusResult waiting() {
        Open115GetStatusResult open115GetStatusResult = new Open115GetStatusResult();
        open115GetStatusResult.setStatus("waiting");
        return open115GetStatusResult;
    }

    /**
     * 创建用户已扫码等待确认的响应对象
     *
     * @param msg 提示消息
     * @return 状态为 scanning 的结果对象
     */
    public static Open115GetStatusResult scanning(String msg) {
        Open115GetStatusResult open115GetStatusResult = new Open115GetStatusResult();
        open115GetStatusResult.setStatus("scanning");
        open115GetStatusResult.setMsg(msg);
        return open115GetStatusResult;
    }

    /**
     * 创建授权成功的响应对象
     *
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param expiredAt    过期时间（Unix 时间戳）
     * @return 状态为 success 的结果对象
     */
    public static Open115GetStatusResult success(String accessToken, String refreshToken, Integer expiredAt) {
        Open115GetStatusResult open115GetStatusResult = new Open115GetStatusResult();
        open115GetStatusResult.setStatus("success");
        open115GetStatusResult.setAccessToken(accessToken);
        open115GetStatusResult.setRefreshToken(refreshToken);
        open115GetStatusResult.setExpiredAt(expiredAt);
        return open115GetStatusResult;
    }


}
