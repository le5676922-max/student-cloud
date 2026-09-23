package im.tlgxy.cloud.module.admin.model.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 规则匹配测试请求参数。
 * <p>
 * 用于管理后台的规则匹配测试功能，管理员可以输入规则类型、规则表达式和测试值，
 * 系统会返回第一个匹配成功的规则。
 *
 * @author zhaojun
 */
@Data
public class TestRuleMatcherRequest {

    /**
     * 规则类型，用于确定使用哪种规则匹配器（如 IP 规则、正则表达式规则等）
     */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /**
     * 规则表达式列表，多个规则之间用换行符分隔
     */
    @NotBlank(message = "规则不能为空")
    private String rules;

    /**
     * 待测试的值，系统将用该值去匹配规则列表中的规则
     */
    @NotBlank(message = "测试值不能为空")
    private String testValue;

}