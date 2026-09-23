package im.tlgxy.cloud.module.storage.enums;

/**
 * 存储源参数项注解属性枚举
 * <p>
 * 定义了存储源参数配置注解（@StorageParamItem）中支持的所有属性名称。
 * 在反射处理存储源参数定义时，通过此枚举值来识别和解析注解中的各个属性字段。
 * </p>
 * <p>
 * 各枚举值含义：
 * <ul>
 *   <li>PRO - 是否为专业版功能</li>
 *   <li>ORDER - 参数显示排序</li>
 *   <li>KEY - 参数的唯一标识键</li>
 *   <li>NAME - 参数的显示名称</li>
 *   <li>TYPE - 参数的类型</li>
 *   <li>OPTIONS - 参数的可选项列表</li>
 *   <li>OPTIONS_CLASS - 动态获取选项的类</li>
 *   <li>OPTION_ALLOW_CREATE - 是否允许创建新选项</li>
 *   <li>REQUIRED - 是否必填</li>
 *   <li>DEFAULT_VALUE - 参数的默认值</li>
 *   <li>DESCRIPTION - 参数的描述信息</li>
 *   <li>LINK - 参数关联的链接地址</li>
 *   <li>LINK_NAME - 参数关联链接的显示名称</li>
 *   <li>IGNORE_INPUT - 是否忽略输入</li>
 *   <li>CONDITION - 参数显示条件</li>
 *   <li>HIDDEN - 是否隐藏</li>
 * </ul>
 * </p>
 */
public enum StorageParamItemAnnoEnum {

    /** 是否为专业版功能 */
    PRO,
    /** 参数显示排序 */
    ORDER,
    /** 参数的唯一标识键 */
    KEY,
    /** 参数的显示名称 */
    NAME,
    /** 参数的类型 */
    TYPE,
    /** 参数的可选项列表 */
    OPTIONS,
    /** 动态获取选项的接口实现类 */
    OPTIONS_CLASS,
    /** 是否允许用户创建新的选项值 */
    OPTION_ALLOW_CREATE,
    /** 是否必填 */
    REQUIRED,
    /** 参数的默认值 */
    DEFAULT_VALUE,
    /** 参数的描述信息 */
    DESCRIPTION,
    /** 参数关联的帮助链接地址 */
    LINK,
    /** 参数关联的帮助链接显示名称 */
    LINK_NAME,
    /** 是否忽略前端输入（如系统自动填充的字段） */
    IGNORE_INPUT,
    /** 控制参数显示的条件表达式 */
    CONDITION,
    /** 是否在前端页面隐藏该参数 */
    HIDDEN
}
