package im.tlgxy.cloud.module.storage.model.result;

import lombok.Data;

/**
 * 115 网盘开放平台设备码授权结果类
 * <p>
 * 封装 115 网盘开放平台 OAuth 设备码授权流程中，
 * 第一步获取设备码的响应结果。
 * 包含用户 ID、时间戳、签名、验证码和二维码图片地址等信息，
 * 用于后续的扫码授权流程。
 * </p>
 */
@Data
public class Open115AuthDeviceCodeResult {

    /**
     * 115 网盘用户唯一标识（UID）
     */
    private String uid;

    /**
     * 请求时间戳
     */
    private Integer time;

    /**
     * 请求签名，用于验证请求合法性
     */
    private String sign;

    /**
     * PKCE 验证码（Code Verifier），用于 OAuth 2.0 PKCE 流程
     */
    private String codeVerifier;

    /**
     * 二维码图片地址，用户扫码后完成授权
     */
    private String qrcode;

}
