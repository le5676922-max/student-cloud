package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.matcher.IRuleMatcher;
import im.tlgxy.cloud.core.util.matcher.RuleMatcherFactory;
import im.tlgxy.cloud.module.admin.model.request.TestRuleMatcherRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规则匹配测试控制器，用于在管理后台测试规则表达式是否能正确匹配目标值。
 * <p>
 * 支持多种规则类型（如 IP 规则、文件名规则等），规则之间用换行符分隔。
 * 匹配成功时返回第一个匹配到的规则表达式。
 *
 * @author zhaojun
 */
@Tag(name = "规则匹配辅助 Controller")
@Slf4j
@RequestMapping("/admin")
@RestController
public class RuleMatcherTestController {

    /**
     * 根据传入的规则类型、规则表达式列表和测试值，测试规则是否匹配。
     * <p>
     * 规则支持多个，用换行符（\n）分割。遍历所有规则，返回第一个匹配成功的规则表达式行。
     * 如果请求参数为空、规则为空或测试值为空，则返回 null。
     *
     * @param testRuleMatcherRequest 测试规则匹配请求，包含规则类型、规则列表和测试值
     * @return 匹配成功的第一个表达式字符串；未匹配或参数为空时返回 null
     */
    @PostMapping("/rule-test")
    public AjaxJson<String> testRule(@RequestBody @Valid TestRuleMatcherRequest testRuleMatcherRequest) {
        // 请求体为空，直接返回 null
        if (testRuleMatcherRequest == null) {
            return AjaxJson.getSuccessData(null);
        }
        String rules = testRuleMatcherRequest.getRules();
        String testValue = testRuleMatcherRequest.getTestValue();
        // 规则或测试值为空，直接返回 null
        if (StringUtils.isBlank(testValue) || StringUtils.isBlank(rules)) {
            return AjaxJson.getSuccessData(null);
        }

        // 将规则字符串按换行符拆分为规则列表
        List<String> ruleList = StringUtils.split(rules, StringUtils.LF);
        // 根据规则类型获取对应的规则匹配器
        IRuleMatcher ipRuleMatcher = RuleMatcherFactory.getRuleMatcher(testRuleMatcherRequest.getRuleType());
        // 遍历规则列表，返回第一个匹配成功的规则表达式
        return AjaxJson.getSuccessData(ipRuleMatcher.matchAnyReturnFirst(ruleList, testValue));
    }

}