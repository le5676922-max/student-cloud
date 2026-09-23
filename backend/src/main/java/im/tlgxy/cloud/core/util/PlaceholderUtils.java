package im.tlgxy.cloud.core.util;

import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 占位符解析与替换工具类
 * <p>
 * 用于解析配置文件或模板字符串中的占位符（格式为 {@code ${变量名}}），
 * 并将其替换为实际值。支持两种解析方式：
 * <ul>
 *     <li><b>从 Spring 环境变量中解析：</b>调用 {@link #resolvePlaceholdersBySpringProperties(String)}，
 *         自动从 Spring 的 {@code Environment} 中读取配置值进行替换</li>
 *     <li><b>从自定义 Map 中解析：</b>调用 {@link #resolvePlaceholders(String, Map)}，
 *         使用调用方传入的键值对进行替换</li>
 * </ul>
 * <p>
 * 占位符格式示例：{@code "我的名字是: ${name}, 我的年龄是: ${age}"}
 * <p>
 * 此外还提供占位符名称提取功能，用于获取模板中所有占位符的名称列表。
 *
 * @author zhaojun
 */
@Slf4j
public class PlaceholderUtils {

    /**
     * 占位符前缀："${"
     */
    public static final String PLACEHOLDER_PREFIX = "${";

    /**
     * 占位符后缀："}"
     */
    public static final String PLACEHOLDER_SUFFIX = "}";


    /**
     * 使用 Spring 环境变量解析模板中的占位符
     * <p>
     * 从模板字符串中提取第一个占位符的名称，然后从 Spring 的 {@code Environment}
     *（即 application.yml / application.properties）中查找对应的属性值进行替换。
     * 如果未找到对应的属性值，则该占位符保持原样。
     * <p>
     * <b>注意：</b>必须在 Spring 容器已启动的环境下使用，否则会抛出异常。
     * 且该方法只处理第一个占位符，适用于单占位符的简单模板。
     *
     * @param formatStr 包含占位符的模板字符串，如 "{@code /data/${storageKey}/doc}"
     * @return 替换后的字符串；如果模板中不包含占位符则原样返回
     */
    public static String resolvePlaceholdersBySpringProperties(String formatStr) {
        // 提取模板中第一个占位符的名称
        String placeholderName = getFirstPlaceholderName(formatStr);
        if (StringUtils.isEmpty(placeholderName)) {
            // 没有占位符，直接返回原始字符串
            return formatStr;
        }

        // 从 Spring 环境中读取占位符对应的属性值
        String propertyValue = SpringUtil.getProperty(placeholderName);
        Map<String, String> map = new HashMap<>();
        map.put(placeholderName, propertyValue);
        // 委托给 Map 版本的解析方法完成实际的替换
        return resolvePlaceholders(formatStr, map);
    }


    /**
     * 使用自定义参数 Map 解析模板中的所有占位符
     * <p>
     * 扫描模板字符串，查找所有 {@code ${...}} 格式的占位符，
     * 并尝试从传入的参数 Map 中查找对应的值进行替换。
     * <p>
     * 扫描策略：
     * <ol>
     *     <li>从字符串开头逐个查找 {@code ${} 前缀</li>
     *     <li>找到前缀后，继续查找对应的 {@code }} 后缀</li>
     *     <li>提取占位符名称，从 Map 中查找对应的值</li>
     *     <li>如果找到值则替换，否则保留原占位符并记录警告日志</li>
     *     <li>从替换后的位置继续向后扫描，直到处理完所有占位符</li>
     * </ol>
     *
     * @param formatStr 包含占位符的模板字符串，如 "{@code 服务器: ${host}, 端口: ${port}}"
     * @param parameter 占位符名称与值的映射 Map，Key 为占位符名称（不含 ${} 包裹），Value 为替换值
     * @return 替换后的字符串；如果参数 Map 为空或 null 则原样返回
     */
    public static String resolvePlaceholders(String formatStr, Map<String, String> parameter) {
        // 如果参数为空，无需进行任何替换
        if (parameter == null || parameter.isEmpty()) {
            return formatStr;
        }

        // 使用 StringBuilder 进行高效的字符串替换操作
        StringBuilder sb = new StringBuilder(formatStr);
        // 查找第一个占位符前缀的位置
        int startIndex = sb.indexOf(PLACEHOLDER_PREFIX);
        while (startIndex != -1) {
            // 查找与当前前缀匹配的后缀
            int endIndex = sb.indexOf(PLACEHOLDER_SUFFIX, startIndex + PLACEHOLDER_PREFIX.length());
            if (endIndex != -1) {
                // 提取占位符的名称（去掉 ${ 和 }）
                String placeholder = sb.substring(startIndex + PLACEHOLDER_PREFIX.length(), endIndex);
                // 计算当前占位符结束后的下一个搜索起始位置
                int nextIndex = endIndex + PLACEHOLDER_SUFFIX.length();
                try {
                    // 从参数 Map 中查找对应的值
                    String propVal = parameter.get(placeholder);
                    if (propVal != null) {
                        // 找到值则执行替换
                        sb.replace(startIndex, endIndex + PLACEHOLDER_SUFFIX.length(), propVal);
                        // 更新下次搜索的起始位置（替换后原位置之后的索引变了）
                        nextIndex = startIndex + propVal.length();
                    } else {
                        // 未找到值时保留原占位符，并记录警告日志
                        log.warn("Could not resolve placeholder '{}' in [{}] ", placeholder, formatStr);
                    }
                } catch (Exception ex) {
                    // 捕获替换过程中的异常，记录错误但不中断处理
                    log.error("Could not resolve placeholder '{}' in [{}]: ", placeholder, formatStr, ex);
                }
                // 从处理后的位置继续向后查找下一个占位符
                startIndex = sb.indexOf(PLACEHOLDER_PREFIX, nextIndex);
            } else {
                // 没有找到配对的结束标记，终止扫描
                startIndex = -1;
            }
        }
        return sb.toString();
    }


    /**
     * 获取模板字符串中第一个占位符的名称
     * <p>
     * 例如，对于模板 {@code "我的名字是: ${name}, 我的年龄是: ${age}"}，
     * 此方法返回 "{@code name}"。
     *
     * @param formatStr 包含占位符的模板字符串
     * @return 第一个占位符的名称（不含 ${} 包裹）；如果没有占位符则返回 null
     */
    public static String getFirstPlaceholderName(String formatStr) {
        List<String> list = getPlaceholderNames(formatStr);
        if (CollectionUtils.isNotEmpty(list)) {
            return list.getFirst();
        }
        return null;
    }


    /**
     * 获取模板字符串中所有占位符的名称列表
     * <p>
     * 扫描整个模板字符串，提取所有 {@code ${...}} 格式的占位符名称。
     * <p>
     * 例如，对于模板 {@code "我的名字是: ${name}, 我的年龄是: ${age}"}，
     * 此方法返回 {@code ["name", "age"]}。
     * <p>
     * 占位符按照在模板中的出现顺序排列。
     *
     * @param formatStr 包含占位符的模板字符串
     * @return 占位符名称列表（已去重且按出现顺序排列）；如果模板为空或无占位符则返回空列表
     */
    public static List<String> getPlaceholderNames(String formatStr) {
        // 空字符串直接返回空列表
        if (StringUtils.isEmpty(formatStr)) {
            return Collections.emptyList();
        }

        List<String> placeholderNameList = new ArrayList<>();

        // 使用 StringBuilder 方便进行索引操作
        StringBuilder sb = new StringBuilder(formatStr);
        // 查找第一个占位符前缀
        int startIndex = sb.indexOf(PLACEHOLDER_PREFIX);
        while (startIndex != -1) {
            // 查找对应的后缀
            int endIndex = sb.indexOf(PLACEHOLDER_SUFFIX, startIndex + PLACEHOLDER_PREFIX.length());
            if (endIndex != -1) {
                // 提取占位符名称并添加到结果列表
                String placeholder = sb.substring(startIndex + PLACEHOLDER_PREFIX.length(), endIndex);
                int nextIndex = endIndex + PLACEHOLDER_SUFFIX.length();
                // 从当前占位符之后继续查找下一个占位符
                startIndex = sb.indexOf(PLACEHOLDER_PREFIX, nextIndex);
                placeholderNameList.add(placeholder);
            } else {
                // 没有找到配对的结束标记，终止扫描
                startIndex = -1;
            }
        }
        return placeholderNameList;
    }

}