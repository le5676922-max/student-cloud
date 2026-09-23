package im.tlgxy.cloud.core.config.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 自动填充处理器。
 * <p>
 * 实现了 {@link MetaObjectHandler} 接口，用于在执行数据库插入（INSERT）和更新（UPDATE）操作时，
 * 自动填充实体类中的 <b>createTime</b>（创建时间）和 <b>updateTime</b>（更新时间）字段，
 * 无需在业务代码中手动设置这些审计字段。
 * </p>
 * <p>
 * 使用前提：
 * </p>
 * <ol>
 *   <li>实体类中需要有 createTime 和 updateTime 字段，类型为 Date。</li>
 *   <li>字段上需要使用 MyBatis-Plus 的 @TableField 注解标注填充策略：
 *       <ul>
 *         <li>createTime：{@code @TableField(fill = FieldFill.INSERT)}</li>
 *         <li>updateTime：{@code @TableField(fill = FieldFill.INSERT_UPDATE)}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入操作时的自动填充方法。
     * <p>
     * 当执行 INSERT 操作时，如果实体对象的 createTime 字段为 null，
     * 则自动填充为当前时间。
     * </p>
     *
     * @param metaObject MyBatis 元对象，包含了要插入的实体属性信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // strictInsertFill 在值为 null 时才进行填充，如果已有值则保留原值
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
    }

    /**
     * 更新操作时的自动填充方法。
     * <p>
     * 当执行 UPDATE 操作时，如果实体对象的 updateTime 字段为 null，
     * 则自动填充为当前时间。
     * </p>
     *
     * @param metaObject MyBatis 元对象，包含了要更新的实体属性信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // strictUpdateFill 在值为 null 时才进行填充，如果已有值则保留原值
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
    }
}
