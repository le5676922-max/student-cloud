package im.tlgxy.cloud.core.constant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * TlgxyCloud 系统常量配置类
 * <p>
 * 用于管理 TlgxyCloud 系统中的全局常量配置，这些配置可以通过 Spring 的 {@link Value} 注解
 * 从配置文件（如 application.yml）中动态注入，实现配置的外部化管理。
 * </p>
 *
 * @author zhaojun
 */
@Configuration
public class TlgxyCloudConstant {

    /**
     * 文本文件预览的最大文件大小限制（单位：KB）
     * <p>
     * 当用户在网页中预览文本文件内容时，超过此大小的文件将不会被加载预览，
     * 以防止大文件导致浏览器卡顿或内存溢出。默认值为 100KB。
     * </p>
     */
    public static Long TEXT_MAX_FILE_SIZE_KB = 100L;

    /**
     * 通过 Spring 配置注入设置文本文件预览的最大大小
     * <p>
     * 该方法由 Spring 自动调用，从配置文件中的 {@code tongligong-student-cloud.preview.text.maxFileSizeKb} 读取值
     * 并赋值给静态变量 {@link #TEXT_MAX_FILE_SIZE_KB}。使用 {@code required = false} 表示该配置项非必填，
     * 如果配置文件中未指定则保持默认值。
     * </p>
     *
     * @param maxFileSizeKb 配置文件中读取的最大文件大小（KB），可能为 null
     */
    @Autowired(required = false)
    public void setTextMaxFileSizeMb(@Value("${tongligong-student-cloud.preview.text.maxFileSizeKb}") Long maxFileSizeKb) {
        TlgxyCloudConstant.TEXT_MAX_FILE_SIZE_KB = maxFileSizeKb;
    }

}
