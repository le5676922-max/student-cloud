package im.tlgxy.cloud.core.model.request;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Objects;

/**
 * 通用分页查询请求对象
 * <p>
 * 封装了分页查询的通用参数，其他需要分页的业务请求类可以继承此类来复用分页逻辑。
 * 与 MyBatis-Plus 的分页插件配合使用。
 * </p>
 * <p>
 * 默认配置：
 * <ul>
 *   <li>默认第 1 页</li>
 *   <li>默认每页 10 条记录</li>
 *   <li>默认按创建时间（create_date）降序排列</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
public class PageQueryRequest {

	/**
	 * 当前页码，从 1 开始计数，默认为第 1 页
	 */
	@Schema(title="分页页数")
	private Integer page = 1;

	/**
	 * 每页显示的记录条数，默认为 10
	 */
	@Schema(title="每页条数")
	private Integer limit = 10;

	/**
	 * 排序字段名，默认为 "create_date"（创建时间）
	 */
	@Schema(title="排序字段")
	private String orderBy = "create_date";

	/**
	 * 排序方向，默认为 "desc"（降序），可选值为 "asc"（升序）或 "desc"（降序）
	 */
	@Schema(title="排序顺序")
	private String orderDirection = "desc";

    /**
     * 获取 MyBatis-Plus 排序对象
     * <p>
     * 根据 {@link #orderBy} 和 {@link #orderDirection} 生成 MyBatis-Plus 分页插件
     * 所需的 {@link OrderItem} 对象。当 orderDirection 为 "asc" 时升序排列，否则降序排列。
     * </p>
     *
     * @return MyBatis-Plus 的排序项对象
     */
    public OrderItem getOrderItem() {
        // 判断是否为升序（asc），如果不等于 asc 则默认为降序
        boolean asc = Objects.equals(orderDirection, "asc");
        return asc ? OrderItem.asc(orderBy) : OrderItem.desc(orderBy);
    }

}
