package im.tlgxy.cloud.module.storage.model.dto;

import lombok.Data;

import java.util.Date;

/**
 * 令牌刷新信息数据传输对象（DTO）
 * <p>
 * 封装令牌刷新操作返回的信息，包括新的访问令牌（Access Token）、
 * 刷新令牌（Refresh Token）、会话令牌（Session Token，用于 AWS 等需要临时凭证的服务）
 * 以及令牌的过期时间。
 * </p>
 * <p>
 * 支持两种过期时间格式：
 * <ul>
 *   <li>绝对时间戳（10 位秒级）：直接表示 Unix 时间戳，如 1752994685</li>
 *   <li>相对过期秒数：表示从当前时间起多少秒后过期</li>
 * </ul>
 * 系统通过值的大小自动判断格式：大于 10 亿的为绝对时间戳，否则为相对过期秒数。
 * </p>
 */
@Data
public class RefreshTokenInfoDTO {

    /**
     * 访问令牌，用于访问受保护的资源（如调用云存储 API 获取文件列表）
     */
    private String accessToken;

    /**
     * 刷新令牌，当访问令牌过期后，用于获取新的访问令牌
     */
    private String refreshToken;

    /**
     * 会话令牌，通常用于 AWS STS（Security Token Service）返回的临时凭证，
     * 在 S3 兼容存储的临时授权场景中使用
     */
    private String sessionToken;

    /**
     * 过期时间戳（单位：秒）
     * 可能是绝对时间戳（10位数字）或相对过期秒数
     */
    private Integer expiredAt;

    /**
     * 将过期时间戳转换为 Java Date 对象
     * <p>
     * 自动判断 expiredAt 的格式：
     * <ul>
     *   <li>值大于 10 亿：视为绝对秒级时间戳，直接乘以 1000 转为毫秒</li>
     *   <li>值小于等于 10 亿：视为相对秒数，加上当前时间的秒数后再转换为毫秒</li>
     * </ul>
     * </p>
     *
     * @return 令牌过期的 Java Date 对象，如果 expiredAt 为 null 则返回 null
     */
    public Date getExpiredAtDate() {
        if (expiredAt == null) {
            return null;
        }
        // 如果 expiredAt 是 10 位秒级绝对时间戳
        if (expiredAt > 1_000_000_000) {
            return new Date(expiredAt * 1000L);
        } else {
            // 否则认为 expiredAt 是相对过期秒数，加上当前时间
            return new Date((expiredAt + System.currentTimeMillis() / 1000) * 1000L);
        }
    }

    /**
     * 创建令牌刷新成功的结果对象
     * <p>
     * 自动判断 expiredAt 参数格式，如果传入了相对过期秒数会自动转换为绝对时间戳。
     * </p>
     *
     * @param accessToken   新的访问令牌
     * @param refreshToken  新的刷新令牌
     * @param sessionToken  会话令牌（S3 等场景使用，可为 null）
     * @param expiredAt     过期时间（秒，支持绝对时间戳和相对秒数两种格式）
     * @return 刷新信息 DTO 对象
     */
    public static RefreshTokenInfoDTO success(String accessToken, String refreshToken, String sessionToken, Integer expiredAt) {
        RefreshTokenInfoDTO token = new RefreshTokenInfoDTO();
        token.setAccessToken(accessToken);
        token.setRefreshToken(refreshToken);
        token.setSessionToken(sessionToken);

        if (expiredAt != null) {
            // 如果 expiredAt 是 10 位秒级绝对时间戳，直接使用
            if (expiredAt > 1_000_000_000) {
                token.setExpiredAt(expiredAt);
            } else {
                // 否则认为 expiredAt 是相对过期秒数，转为绝对时间戳
                token.setExpiredAt(expiredAt + (int) (System.currentTimeMillis() / 1000));
            }
        }
        return token;
    }

    /**
     * 创建令牌刷新成功的结果对象（无会话令牌的便捷方法）
     *
     * @param accessToken   新的访问令牌
     * @param refreshToken  新的刷新令牌
     * @param expiredAt     过期时间（秒）
     * @return 刷新信息 DTO 对象
     */
    public static RefreshTokenInfoDTO success(String accessToken, String refreshToken, Integer expiredAt) {
        return success(accessToken, refreshToken, null, expiredAt);
    }

}
