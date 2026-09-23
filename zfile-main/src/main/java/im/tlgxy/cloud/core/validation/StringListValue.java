package im.tlgxy.cloud.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 字符串列表值校验注解。
 * <p>
 *     用于校验一个字段的值是否在预设的允许值列表中。
 *     该注解基于 Jakarta Bean Validation（JSR 380）规范实现，
 *     通过关联的校验器 {@link StringListValueConstraintValidator} 执行实际的校验逻辑。
 * </p>
 *
 * <b>使用示例：</b>
 * <pre>{@code
 * @StringListValue(vals = {"admin", "user", "guest"}, message = "角色值不在允许范围内")
 * private String role;
 * }</pre>
 *
 * <p>
 *     注解可标记在以下位置：
 *     <ul>
 *         <li>方法（{@link ElementType#METHOD}）</li>
 *         <li>字段（{@link ElementType#FIELD}）</li>
 *         <li>注解类型（{@link ElementType#ANNOTATION_TYPE}）</li>
 *     </ul>
 *     注解在运行时保留（{@link RetentionPolicy#RUNTIME}），
 *     以便校验框架在运行时通过反射获取注解信息。
 * </p>
 *
 * @author zhaojun
 */
@Documented
@Constraint(validatedBy = { StringListValueConstraintValidator.class })
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface StringListValue {

    /**
     * 校验失败时的默认错误消息。
     * <p>
     *     可以通过注解属性自定义，例如：
     *     {@code @StringListValue(vals = {"a","b"}, message = "值不合法")}
     * </p>
     *
     * @return 错误消息字符串，默认为空字符串
     */
    String message() default "";

    /**
     * 校验分组，用于在不同场景下执行不同的校验规则。
     * <p>
     *     默认值为空数组，表示属于默认分组（{@link jakarta.validation.groups.Default}）。
     * </p>
     *
     * @return 校验分组 Class 数组
     */
    Class<?>[] groups() default { };

    /**
     * 负载（Payload），用于携带额外的元数据信息。
     * <p>
     *     通常由校验框架的客户端使用，可以为校验失败的字段附加额外的上下文信息。
     * </p>
     *
     * @return Payload Class 数组，默认为空数组
     */
    Class<? extends Payload>[] payload() default { };

    /**
     * 允许的值列表。
     * <p>
     *     定义了被校验字段可以取值的合法范围。
     *     校验时，字段的值必须包含在该数组中才能通过校验；
     *     如果字段值为空（{@code null} 或空字符串），则默认跳过校验并视为通过。
     * </p>
     *
     * @return 允许的字符串值数组，默认为空数组
     */
    String[] vals() default { };

}