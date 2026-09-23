package im.tlgxy.cloud.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件大小转换工具类
 * <p>
 * 用于将带单位的文件大小字符串（如 "10MB"、"1.5GB"、"500KB" 等）转换为字节数（long 类型）。
 * 支持二进制单位（B、KB/KIB、MB/MIB、GB/GIB、TB/TIB、PB/PIB），
 * 单位不区分大小写，数值部分可以是整数或小数。
 * </p>
 *
 * @author zhaojun
 */
public class FileSizeConverter {

    /**
     * KB 单位对应的字节数（1024）
     */
    private static final long KB_FACTOR = 1024L;

    /**
     * MB 单位对应的字节数（1024 * 1024）
     */
    private static final long MB_FACTOR = 1024L * KB_FACTOR;

    /**
     * GB 单位对应的字节数（1024 * 1024 * 1024）
     */
    private static final long GB_FACTOR = 1024L * MB_FACTOR;

    /**
     * TB 单位对应的字节数（1024 * 1024 * 1024 * 1024）
     */
    private static final long TB_FACTOR = 1024L * GB_FACTOR;

    /**
     * PB 单位对应的字节数（1024 * 1024 * 1024 * 1024 * 1024）
     */
    private static final long PB_FACTOR = 1024L * TB_FACTOR;

    /**
     * 文件大小字符串的正则匹配模式
     * <p>
     * 匹配格式：数值部分（支持整数和小数）+ 可选的空格 + 单位部分（字母组成）。
     * 例如："10MB"、"1.5 GB"、"500kib" 都能正确匹配。
     * </p>
     */
    private static final Pattern FILE_SIZE_PATTERN = Pattern.compile("([\\d.]+)\\s*([a-zA-Z]+)");

    /**
     * 将带单位的文件大小字符串转换为字节数
     * <p>
     * 支持的格式示例：
     * </p>
     * <ul>
     *     <li>"100B" -> 100 字节</li>
     *     <li>"10KB" 或 "10KIB" -> 10240 字节</li>
     *     <li>"1.5MB" 或 "1.5MIB" -> 1572864 字节</li>
     *     <li>"2GB" 或 "2GIB" -> 2147483648 字节</li>
     *     <li>"1TB" 或 "1TIB" -> 1099511627776 字节</li>
     *     <li>"1PB" 或 "1PIB" -> 1125899906842624 字节</li>
     * </ul>
     * <p>
     * 数值和单位之间可以有空格，单位不区分大小写。计算结果会进行四舍五入取整。
     * </p>
     *
     * @param   sizeStr
     *          带单位的文件大小字符串，如 "10MB"、"1.5GB"
     *
     * @return  对应的字节数（long 类型）
     *
     * @throws IllegalArgumentException 如果输入字符串为空、格式不正确、数字格式无效或单位不支持
     * @throws ArithmeticException 如果转换后的字节数超过了 Long 类型的最大值
     */
    public static long convertFileSizeToBytes(String sizeStr) {
        // 输入校验：字符串不能为空
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            throw new IllegalArgumentException("输入字符串不能为空");
        }

        // 使用正则表达式匹配数值部分和单位部分
        Matcher matcher = FILE_SIZE_PATTERN.matcher(sizeStr.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException("无效的文件大小格式: " + sizeStr);
        }

        // 提取数值字符串（group 1）
        String valueStr = matcher.group(1);
        // 提取单位字符串并转换为大写（group 2）
        String unitStr = matcher.group(2).toUpperCase();

        // 将数值字符串解析为 double 类型，支持小数
        double value;
        try {
            value = Double.parseDouble(valueStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的数字格式: " + valueStr, e);
        }

        // 校验：文件大小不能为负数
        if (value < 0) {
            throw new IllegalArgumentException("文件大小不能为负数: " + valueStr);
        }

        // 根据单位确定乘数因子
        long multiplier = switch (unitStr) {
            case "B" ->
                    1L;
            case "KB", "KIB" ->
                    KB_FACTOR;
            case "MB", "MIB" ->
                    MB_FACTOR;
            case "GB", "GIB" ->
                    GB_FACTOR;
            case "TB", "TIB" ->
                    TB_FACTOR;
            case "PB", "PIB" ->
                    PB_FACTOR;
            default -> throw new IllegalArgumentException("不支持的单位: " + unitStr + " (支持 B, KB, MB, GB, TB, PB)");
        };

        // 计算字节数（double 类型中间结果）
        double bytesDouble = value * multiplier;
        // 校验：结果不能超过 Long 类型的最大值
        if (bytesDouble > Long.MAX_VALUE) {
            throw new ArithmeticException("转换后的字节数超过了 Long 类型的最大值: " + bytesDouble);
        }

        // 四舍五入返回 long 类型结果
        return Math.round(bytesDouble);
    }

}