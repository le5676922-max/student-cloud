package im.tlgxy.cloud.core.util;

import org.apache.commons.io.FilenameUtils;

/**
 * 文件相关工具类
 * <p>
 * 提供文件路径和文件名的常用操作，包括：
 * </p>
 * <ul>
 *     <li>从完整路径中提取文件名（去除路径前缀）</li>
 *     <li>从完整路径中提取父目录路径</li>
 *     <li>从文件名中提取文件扩展名</li>
 * </ul>
 *
 * @author zhaojun
 */
public class FileUtils {

    /**
     * 从完整路径中提取文件名（去除路径部分）
     * <p>
     * 查找最后一个斜杠（/）的位置，返回其后的部分作为文件名。
     * 如果没有斜杠，则整个字符串即为文件名。
     * </p>
     *
     * @param   fileName
     *          完整的文件路径，如 "/home/user/document.txt" 或 "document.txt"
     *
     * @return  文件名（不含路径前缀），如果输入为 null 则返回 null
     */
    public static String getName(final String fileName) {
        // 输入为 null 时返回 null
        if (fileName == null) {
            return null;
        }

        // 查找最后一个斜杠的位置
        int i = fileName.lastIndexOf(CharSequenceUtil.SLASH_CHAR);
        // 如果存在斜杠，截取斜杠之后的部分作为文件名
        if (i >= 0 && i <= fileName.length() - 1) {
            return fileName.substring(i + 1);
        }

        // 没有斜杠，整个字符串即为文件名
        return fileName;
    }

    /**
     * 从完整路径中提取父目录路径
     * <p>
     * 移除路径末尾的分隔符后，使用 Apache Commons IO 的
     * {@link FilenameUtils#getFullPathNoEndSeparator} 方法获取父目录路径。
     * 如果父目录为空或不存在，返回根目录 "/"。
     * </p>
     *
     * @param   fileName
     *          完整的文件路径，如 "/home/user/document.txt"
     *
     * @return  父目录路径，末尾不带分隔符。如果父目录为空则返回 "/"
     */
    public static String getParentPath(final String fileName) {
        // 先去除路径末尾的斜杠，然后获取不带末尾分隔符的父目录路径
        String fullPathNoEndSeparator = FilenameUtils.getFullPathNoEndSeparator(StringUtils.trimEndSlashes(fileName));
        // 如果父目录为空，返回根目录
        if (fullPathNoEndSeparator == null || fullPathNoEndSeparator.isEmpty()) {
            return StringUtils.SLASH;
        }
        return fullPathNoEndSeparator;
    }

    /**
     * 从文件名中提取文件扩展名
     * <p>
     * 查找文件名中最后一个点号（.），返回其后的部分作为扩展名。
     * 点号不能在开头或末尾位置（即 ".gitignore" 不算有扩展名，"file." 也不算）。
     * 如果没有有效的扩展名，返回空字符串。
     * </p>
     *
     * @param   fileName
     *          文件名或文件路径，如 "document.txt" 或 "/path/to/document.txt"
     *
     * @return  文件的扩展名（不含点号），如 "txt"。如果没有扩展名则返回空字符串 ""，
     *          输入为 null 时返回 null
     *
     * @throws IllegalArgumentException 如果参数校验失败（由调用方决定）
     */
    public static String getExtension(final String fileName) throws IllegalArgumentException {
        // 输入为 null 时返回 null
        if (fileName == null) {
            return null;
        }

        // 查找最后一个点号的位置
        int i = fileName.lastIndexOf('.');
        // 点号位置必须大于 0（不在开头）且小于 length-1（不在末尾），才能算作有效的扩展名
        if (i > 0 && i < fileName.length() - 1) {
            return fileName.substring(i + 1);
        }

        // 没有有效的扩展名，返回空字符串
        return "";
    }

}