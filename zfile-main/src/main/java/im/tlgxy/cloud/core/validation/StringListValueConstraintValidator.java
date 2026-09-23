package im.tlgxy.cloud.core.validation;

import im.tlgxy.cloud.core.util.StringUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 字符串列表值校验器，是 {@link StringListValue} 注解的具体校验实现。
 * <p>
 *     该类实现了 Jakarta Bean Validation 的 {@link ConstraintValidator} 接口，
 *     用于校验被 {@link StringListValue} 注解标记的字段值是否在预设的允许值列表中。
 * </p>
 *
 * <b>校验逻辑：</b>
 * <ol>
 *     <li>在初始化阶段，将注解中 {@code vals} 数组的所有值加载到内存中的 {@link HashSet}，
 *         以便后续以 O(1) 时间复杂度进行查找</li>
 *     <li>在 {@link #isValid(String, ConstraintValidatorContext)} 方法中：
 *         <ul>
 *             <li>如果值为空（{@code null} 或空字符串），视为通过校验（返回 {@code true}）</li>
 *             <li>如果值非空，则判断该值是否在预设的允许值集合中</li>
 *         </ul>
 *     </li>
 * </ol>
 *
 * @author zhaojun
 */
public class StringListValueConstraintValidator implements ConstraintValidator<StringListValue, String> {

    /**
     * 存储允许值列表的集合，使用 {@link HashSet} 以保证 O(1) 的查找效率。
     * 在 {@link #initialize(StringListValue)} 方法中初始化。
     */
    private final Set<String> set = new HashSet<>();

    /**
     * 初始化校验器，从注解中读取允许的值列表。
     * <p>
     *     该方法在校验器实例化后由校验框架自动调用一次。
     *     将 {@link StringListValue#vals()} 中定义的所有允许值
     *     加载到内部的 {@link HashSet} 中，供后续校验使用。
     * </p>
     *
     * @param constraintAnnotation
     *          校验注解对象，包含了 {@code vals} 等注解属性值
     */
    @Override
    public void initialize(StringListValue constraintAnnotation) {
        // 从注解中获取允许的值列表
        String[] vals = constraintAnnotation.vals();
        // 将所有允许值添加到 HashSet 中，后续校验时通过 set.contains() 进行高效查找
        set.addAll(Arrays.asList(vals));

    }

    /**
     * 执行实际的校验逻辑，判断字段值是否在允许的范围内。
     * <p>
     *     校验规则：
     *     <ul>
     *         <li>如果 {@code value} 为 {@code null} 或空字符串，视为合法（返回 {@code true}）</li>
     *         <li>否则，判断 {@code value} 是否在初始化时加载的允许值集合中</li>
     *     </ul>
     *     对空值的宽容处理意味着该注解不会强制要求字段必填，
     *     如果同时需要非空校验，应配合 {@code @NotEmpty} 或 {@code @NotBlank} 使用。
     * </p>
     *
     * @param value
     *          需要校验的字段值，可能为 {@code null}
     * @param context
     *          校验上下文，提供校验相关的上下文信息（如约束违例的构建等）
     * @return {@code true} 表示校验通过（值为空或在允许列表中）；
     *         {@code false} 表示校验失败（值不在允许列表中）
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 空值视为合法，不进行进一步校验
        if (StringUtils.isEmpty(value)) {
            return true;
        }
        // 判断值是否在预设的允许值集合中
        return set.contains(value);
    }

}