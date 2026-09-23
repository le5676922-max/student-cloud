package im.tlgxy.cloud.module.link.model.request;

import lombok.Data;

import java.util.List;

/**
 * 批量删除请求参数类
 * <p>
 * 封装批量删除操作所需的 ID 列表，用于短链的批量删除功能。
 * 前端传入一组要删除的短链 ID，后端根据这些 ID 执行批量删除操作。
 * <p>
 * 使用 Lombok {@link Data} 注解自动生成 getter/setter/toString 等方法。
 *
 * @author zhaojun
 */
@Data
public class BatchDeleteRequest {

    /**
     * 要删除的短链 ID 列表
     */
    private List<Integer> ids;

}