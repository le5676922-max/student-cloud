package im.tlgxy.cloud.module.password.model.dto;

import im.tlgxy.cloud.core.exception.ErrorCode;
import lombok.Data;

/**
 * 密码校验结果 DTO
 * <p>
 * 封装密码校验的结果信息，包括：
 * <ul>
 *   <li>校验是否通过（passed）</li>
 *   <li>匹配到的密码规则表达式（pattern）</li>
 *   <li>错误码（errorCode，校验失败时提供具体错误原因）</li>
 * </ul>
 * 提供了静态工厂方法用于快速创建成功和失败的结果对象。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class VerifyResultDTO {

    /**
     * 校验是否通过
     */
    private boolean passed;

    /**
     * 匹配到的密码规则表达式（校验通过时记录）
     */
    private String pattern;

    /**
     * 错误码（校验失败时提供具体错误原因）
     */
    private ErrorCode errorCode;

    /**
     * 创建校验成功的结果（无表达式信息）
     *
     * @return 校验通过的 VerifyResultDTO
     */
    public static VerifyResultDTO success() {
        VerifyResultDTO verifyResultDTO = new VerifyResultDTO();
        verifyResultDTO.setPassed(true);
        return verifyResultDTO;
    }


    /**
     * 创建校验成功的结果（带匹配的表达式信息）
     *
     * @param pattern 匹配到的密码规则表达式
     * @return 校验通过的 VerifyResultDTO
     */
    public static VerifyResultDTO success(String pattern) {
        VerifyResultDTO verifyResultDTO = new VerifyResultDTO();
        verifyResultDTO.setPassed(true);
        verifyResultDTO.setPattern(pattern);
        return verifyResultDTO;
    }


    /**
     * 创建校验失败的结果
     *
     * @param errorCode 失败原因对应的错误码
     * @return 校验失败的 VerifyResultDTO
     */
    public static VerifyResultDTO fail(ErrorCode errorCode) {
        VerifyResultDTO verifyResultDTO = new VerifyResultDTO();
        verifyResultDTO.setPassed(false);
        verifyResultDTO.setErrorCode(errorCode);
        return verifyResultDTO;
    }

}