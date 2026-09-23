package im.tlgxy.cloud.core.util;

import cn.hutool.core.util.NumberUtil;

/**
 * 文件大小/带宽大小转可读字符串工具类
 * <p>
 * 提供将原始字节数转换为人类可读格式（如 "1.5 MB"）的静态方法。
 * 支持两种换算标准：
 * <ul>
 *   <li><b>文件大小换算</b>：以 1024 为进制（二进制标准），适用于磁盘存储场景。</li>
 *   <li><b>带宽大小换算</b>：以 1000 为进制（十进制标准），适用于网络传输速率场景。</li>
 * </ul>
 * 最高支持到 YB（尧字节）级别。
 * </p>
 *
 * @author zhaojun
 */
public class SizeToStrUtils {

    /**
     * 将文件大小（字节数）转换为人类可读的字符串
     * <p>
     * 采用二进制标准：1 KB = 1024 B，1 MB = 1024 KB，依此类推。
     * 结果保留 3 位小数，并附带对应的单位后缀（如 "1.536 MB"）。
     * </p>
     *
     * @param   bytes
     *          文件大小的字节数
     *
     * @return  可读的文件大小字符串，如 <code>"0"</code>（0 字节时）、<code>"1.536 MB"</code>。
     *          格式为：数字 + 空格 + 单位
     */
    public static String bytesToSize(long bytes) {
        // 0 字节直接返回 "0"，避免对数计算 log(0) 为负无穷的问题
        if (bytes == 0) {
            return "0";
        }

        // 以 1024 为基数进行计算（二进制标准，适用于文件存储大小）
        double k = 1024;
        // 支持的容量单位数组，从 B（字节）到 YB（尧字节）
        String[] sizes = new String[]{"B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"};
        // 通过对数计算确定容量单位所在的索引位置
        // 公式：log(bytes) / log(k) 得到以 k 为底的对数，向下取整即为单位索引
        double i = Math.floor(Math.log(bytes) / Math.log(k));
        // 换算到目标单位：bytes / k^i，并保留 3 位小数
        return NumberUtil.round(bytes / Math.pow(k, i), 3) + " " + sizes[(int) i];
    }


    /**
     * 将带宽大小（每秒字节数）转换为人类可读的字符串
     * <p>
     * 采用十进制标准：1 KB = 1000 B，1 MB = 1000 KB，依此类推。
     * 网络带宽通常使用十进制换算（bps -> Kbps -> Mbps ...）。
     * 结果保留 3 位小数，并附带对应的单位后缀。
     * </p>
     *
     * @param   bps
     *          每秒字节数（注意：此处参数名称为 bps，但实际接收的是字节数，非比特数）
     *
     * @return  可读的带宽大小字符串，如 <code>"0"</code>（0 时）、<code>"1.5 MB"</code>。
     *          格式为：数字 + 空格 + 单位
     */
    public static String bpsToSize(long bps) {
        // 0 直接返回 "0"，避免 log(0) 计算问题
        if (bps == 0) {
            return "0";
        }

        // 以 1000 为基数进行计算（十进制标准，适用于网络带宽速率）
        double k = 1000;
        // 支持的容量单位数组，从 B（字节）到 YB（尧字节）
        String[] sizes = new String[]{"B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"};
        // 通过对数计算确定单位所在的索引位置
        double i = Math.floor(Math.log(bps) / Math.log(k));
        // 换算到目标单位：bps / k^i，并保留 3 位小数
        return NumberUtil.round(bps / Math.pow(k, i), 3) + " " + sizes[(int) i];
    }

}