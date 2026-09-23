
package im.tlgxy.cloud.module.storage.model.bo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 又拍云上传认证信息业务对象（BO）
 * <p>
 * 封装又拍云（UpYun）存储上传所需的认证凭证信息。
 * 在文件上传到又拍云之前，先请求又拍云的签名认证接口获取此对象中的信息，
 * 然后携带这些凭证信息执行实际的文件上传操作。
 * </p>
 * <p>
 * 三个字段的含义：
 * <ul>
 *   <li>url - 又拍云文件上传接口的地址</li>
 *   <li>signature - 上传请求的数字签名，用于验证请求合法性</li>
 *   <li>policy - 上传策略的 Base64 编码，定义了上传的约束条件（如过期时间、文件大小限制等）</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
public class AuthModel {

    /** 又拍云文件上传接口的 URL 地址 */
    private String url;

    /** 上传请求的数字签名，由又拍云根据上传策略计算得出，用于验证请求合法性 */
    private String signature;

    /** 上传策略的 Base64 编码字符串，包含上传约束条件（过期时间、允许的文件类型等） */
    private String policy;

}