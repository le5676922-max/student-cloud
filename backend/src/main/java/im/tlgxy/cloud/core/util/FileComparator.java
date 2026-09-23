package im.tlgxy.cloud.core.util;

import cn.hutool.core.comparator.CompareUtil;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;

import java.util.Comparator;

/**
 * 文件比较器
 * <p>
 * 用于对 {@link FileItemResult} 列表进行排序的比较器，实现了 {@link Comparator} 接口。
 * 排序规则如下：
 * </p>
 * <ul>
 *     <li>文件夹始终排在文件前面（即文件夹的排序优先级高于文件）</li>
 *     <li>当两个条目类型相同时，根据 sortBy 字段决定排序依据：
 *         <ul>
 *             <li>"name" - 按名称排序（默认，使用自然排序比较器 {@link NaturalOrderComparator}）</li>
 *             <li>"time" - 按修改时间排序</li>
 *             <li>"size" - 按文件大小排序</li>
 *         </ul>
 *     </li>
 *     <li>默认排序方式为升序（asc），可通过 order 字段指定为降序（desc）</li>
 *     <li>按名称排序时不区分大小写</li>
 * </ul>
 *
 * @author zhaojun
 */
public class FileComparator implements Comparator<FileItemResult> {

    /**
     * 排序依据字段，可选值：name（名称）、time（修改时间）、size（文件大小）
     */
    private String sortBy;

    /**
     * 排序方式，asc 表示升序，desc 表示降序
     */
    private String order;

    /**
     * 构造方法，初始化排序依据和排序方式
     *
     * @param sortBy 排序依据字段
     * @param order  排序方式
     */
    public FileComparator(String sortBy, String order) {
        this.sortBy = sortBy;
        this.order = order;
    }


    /**
     * 比较两个文件条目的大小
     * <p>
     * 具体比较逻辑：
     * </p>
     * <ol>
     *     <li>如果两个条目的类型相同（都是文件夹或都是文件），则根据 sortBy 和 order 进行比较</li>
     *     <li>如果类型不同，文件夹始终排在文件前面</li>
     * </ol>
     *
     * @param   o1
     *          第一个文件条目
     *
     * @param   o2
     *          第二个文件条目
     *
     * @return  如果 o1 应排在 o2 前面，返回负数；如果 o2 应排在 o1 前面，返回正数；相等返回 0
     */
    @Override
    public int compare(FileItemResult o1, FileItemResult o2) {
        // 如果排序依据为空，默认按名称排序
        if (sortBy == null) {
            sortBy = "name";
        }

        // 如果排序方式为空，默认升序
        if (order == null) {
            order = "asc";
        }

        FileTypeEnum o1Type = o1.getType();
        FileTypeEnum o2Type = o2.getType();

        // 使用自然排序比较器处理名称比较（支持数字自然排序，如 "2.txt" 排在 "10.txt" 前面）
        NaturalOrderComparator naturalOrderComparator = new NaturalOrderComparator();

        // 两个条目类型相同时，根据 sortBy 进行排序
        if (o1Type.equals(o2Type)) {
            int result = switch (sortBy) {
                case "time" -> CompareUtil.compare(o1.getTime(), o2.getTime());
                case "size" -> CompareUtil.compare(o1.getSize(), o2.getSize());
                default -> naturalOrderComparator.compare(o1.getName(), o2.getName());
            };
            // 如果是降序，反转比较结果
            return "asc".equals(order) ? result : -result;
        }

        // 类型不同时，文件夹始终排在文件前面
        if (o1Type.equals(FileTypeEnum.FOLDER)) {
            return -1;
        } else {
            return 1;
        }
    }

}