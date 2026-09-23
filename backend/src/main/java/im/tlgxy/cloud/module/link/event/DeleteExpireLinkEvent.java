package im.tlgxy.cloud.module.link.event;

import lombok.Data;

/**
 * 删除过期短链事件
 * <p>
 * 当系统执行删除过期短链操作时发布此事件。
 * 该事件使用 Spring 的事件机制（ApplicationEvent），可以被其他组件监听和处理。
 * <p>
 * 典型的使用场景是：在删除过期短链后通知其他模块执行相关的清理工作，
 * 例如清理缓存中的过期短链 key 等。
 * <p>
 * 当前类是一个标记性事件，不包含额外的业务数据。
 * 使用 Lombok {@link Data} 注解自动生成 getter/setter/toString/equals/hashCode 方法。
 *
 * @author zhaojun
 */
@Data
public class DeleteExpireLinkEvent {
}