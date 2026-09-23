package im.tlgxy.cloud.core.util;
/*
 NaturalOrderComparator.java -- Perform 'natural order' comparisons of strings in Java.
 Copyright (C) 2003 by Pierre-Luc Paour <natorder@paour.com>

 Based on the C version by Martin Pool, of which this is more or less a straight conversion.
 Copyright (C) 2000 by Martin Pool <mbp@humbug.org.au>

 This software is provided 'as-is', without any express or implied
 warranty.  In no event will the authors be held liable for any damages
 arising from the use of this software.

 Permission is granted to anyone to use this software for any purpose,
 including commercial applications, and to alter it and redistribute it
 freely, subject to the following restrictions:

 1. The origin of this software must not be misrepresented; you must not
 claim that you wrote the original software. If you use this software
 in a product, an acknowledgment in the product documentation would be
 appreciated but is not required.
 2. Altered source versions must be plainly marked as such, and must not be
 misrepresented as being the original software.
 3. This notice may not be removed or altered from any source distribution.
 */

import java.util.Comparator;

/**
 * 自然排序比较器，模拟 Windows 资源管理器的文件排序算法
 * <p>
 * 普通的字符串排序（字典序）在排序包含数字的文件名时会出现不符合直觉的结果，
 * 例如 {@code "file2" < "file10"} 在字典序中不成立（因为 '1' < '2'），
 * 但在自然排序中会正确处理数字部分，将 "file2" 排在 "file10" 之前。
 * <p>
 * 该实现基于 Pierre-Luc Paour 的 Java 版本，源自 Martin Pool 的 C 语言版本。
 * <p>
 * 使用方式：
 * <pre>{@code
 *     List<String> list = Arrays.asList("file10.txt", "file2.txt", "file1.txt");
 *     list.sort(new NaturalOrderComparator());
 *     // 结果: ["file1.txt", "file2.txt", "file10.txt"]
 * }</pre>
 *
 * @author zhaojun
 */
public class NaturalOrderComparator implements Comparator<String> {

    /**
     * 字符 '0'，用于前导零的判断和计数
     */
    private static final char ZERO_CHAR = '0';

    /**
     * 比较两个字符串中从当前位置开始的连续数字部分
     * <p>
     * 核心思想：逐个字符比较两个数字子串，直到一方或双方的连续数字结束。
     * 比较规则：
     * <ul>
     *     <li>如果两个数字子串位数不同，位数多的更大</li>
     *     <li>如果位数相同但数值不同，数值大的更大（通过 bias 提前记录差异方向）</li>
     * </ul>
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 比较结果：0 表示相等（会在外层继续比较后续非数字部分），
     *         -1 表示 a<b，+1 表示 a>b
     */
    private int compareRight(String a, String b) {
        int bias = 0, ia = 0, ib = 0;

        // 逐字符比较数字部分。最长连续数字串胜出。
        // 在扫描到两个数字具有相同位数之前，无法确定哪个数字更大，
        // 因此用 bias 提前记住首次发现的大小差异方向。
        for (; ; ia++, ib++) {
            char ca = charAt(a, ia);
            char cb = charAt(b, ib);

            // 双方都不是数字，说明数字部分长度相同，返回预先记录的 bias
            if (!isDigit(ca) && !isDigit(cb)) {
                return bias;
            }
            // 只有 a 不是数字，说明 a 的数字部分更短，a 更小
            if (!isDigit(ca)) {
                return -1;
            }
            // 只有 b 不是数字，说明 b 的数字部分更短，b 更小
            if (!isDigit(cb)) {
                return +1;
            }
            // 双方都到达字符串末尾
            if (ca == 0 && cb == 0) {
                return bias;
            }

            // 在 bias 未被设置时，记录首次发现的大小差异
            if (bias == 0) {
                if (ca < cb) {
                    bias = -1;
                } else if (ca > cb) {
                    bias = +1;
                }
            }
        }
    }

    /**
     * 比较两个字符串的自然顺序
     * <p>
     * 比较逻辑：
     * <ol>
     *     <li>跳过前导空格和零，并记录前导零的数量</li>
     *     <li>如果当前位置都是数字，委托 {@link #compareRight(String, String)} 比较数字部分</li>
     *     <li>如果都不是数字，按字符比较</li>
     * </ol>
     *
     * @param a 第一个字符串，不能为 null
     * @param b 第二个字符串，不能为 null
     * @return 负数表示 a < b，零表示 a == b，正数表示 a > b
     */
    @Override
    public int compare(String a, String b) {
        int ia = 0, ib = 0;
        int nza, nzb;
        char ca, cb;

        while (true) {
            // 重置前导零计数器
            nza = nzb = 0;

            ca = charAt(a, ia);
            cb = charAt(b, ib);

            // 跳过 a 的前导空格和前导零
            while (Character.isSpaceChar(ca) || ca == ZERO_CHAR) {
                if (ca == ZERO_CHAR) {
                    nza++; // 统计连续前导零的数量
                } else {
                    // 遇到空格则重置前导零计数（只统计连续的前导零）
                    nza = 0;
                }

                ca = charAt(a, ++ia);
            }

            // 跳过 b 的前导空格和前导零
            while (Character.isSpaceChar(cb) || cb == '0') {
                if (cb == '0') {
                    nzb++; // 统计连续前导零的数量
                } else {
                    // 遇到空格则重置前导零计数（只统计连续的前导零）
                    nzb = 0;
                }

                cb = charAt(b, ++ib);
            }

            // 如果当前字符都是数字，进入数字比较逻辑
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int bias = compareRight(a.substring(ia), b.substring(ib));
                if (bias != 0) {
                    return bias;
                }
            }

            // 两个字符串同时结束，通过前导零数量和字符串长度来判断
            if (ca == 0 && cb == 0) {
                // 完全相同时，可能还需要按前导零或长度来决定
                return compareEqual(a, b, nza, nzb);
            }
            // 按字符大小比较
            if (ca < cb) {
                return -1;
            }
            if (ca > cb) {
                return +1;
            }

            // 当前字符相等，继续比较下一位
            ++ia;
            ++ib;
        }
    }

    /**
     * 判断字符是否为数字（包括小数点）
     * <p>
     * 将小数点 {@code .} 和逗号 {@code ,} 也视为数字的一部分，
     * 这样在排序包含版本号或小数点的文件名时能正确处理，
     * 例如 "v1.2" 和 "v1.10" 的比较。
     *
     * @param c 待判断的字符
     * @return 是数字（或小数点/逗号）返回 true，否则返回 false
     */
    private static boolean isDigit(char c) {
        return Character.isDigit(c) || c == '.' || c == ',';
    }

    /**
     * 安全地获取字符串指定位置的字符
     * <p>
     * 如果索引超出字符串长度则返回 {@code 0}（即空字符 '\0'），
     * 而不是抛出异常。这样简化了调用方的边界判断逻辑。
     *
     * @param s 源字符串
     * @param i 目标索引位置
     * @return 该位置的字符；如果索引越界则返回 0（空字符）
     */
    private static char charAt(String s, int i) {
        return i >= s.length() ? 0 : s.charAt(i);
    }

    /**
     * 当两个字符串的主体部分比较结果相同时，进行最终判定
     * <p>
     * 判定优先级：
     * <ol>
     *     <li>前导零数量：前导零更多的排在前面（如 "01" < "1"）</li>
     *     <li>字符串长度：较短的排在前面</li>
     *     <li>字典序：作为最终裁决</li>
     * </ol>
     *
     * @param a   第一个字符串
     * @param b   第二个字符串
     * @param nza 字符串 a 的前导零数量
     * @param nzb 字符串 b 的前导零数量
     * @return 比较结果
     */
    private static int compareEqual(String a, String b, int nza, int nzb) {
        // 前导零数量不同，前导零多的更小（排在前面）
        if (nza - nzb != 0) {
            return nza - nzb;
        }

        // 长度不同，较短的更小（排在前面）
        if (a.length() == b.length()) {
            return a.compareTo(b);
        }

        return a.length() - b.length();
    }

}