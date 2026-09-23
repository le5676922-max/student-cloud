package im.tlgxy.cloud.core.util;

/**
 * 字符常量池接口
 * <p>
 * 定义项目中使用到的通用字符常量，方便统一引用和维护。
 * 实现该接口的类可以直接使用这些常量，无需通过类名前缀。
 * </p>
 *
 * @author zhaojun
 */
public interface CharPool {

    /**
     * 斜杠字符常量 '/'
     * <p>ASCII 码值为 47，常用于路径拼接和 URL 处理。</p>
     */
    char SLASH_CHAR = '/';

}
