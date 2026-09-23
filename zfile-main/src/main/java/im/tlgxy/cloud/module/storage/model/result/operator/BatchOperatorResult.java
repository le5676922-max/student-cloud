package im.tlgxy.cloud.module.storage.model.result.operator;

import lombok.Data;

/**
 * 批量操作结果类
 * <p>
 * 封装批量文件操作（如批量删除、批量移动/复制）中
 * 单个文件/文件夹的操作结果。
 * 包含操作是否成功、文件名称、路径以及失败时的错误消息。
 * 提供静态工厂方法用于快速创建成功或失败的响应对象。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class BatchOperatorResult {

    /**
     * 文件/文件夹名称
     */
    private String name;

    /**
     * 文件/文件夹路径
     */
    private String path;

    /**
     * 操作是否成功
     */
    private boolean success;

    /**
     * 操作失败时的错误消息
     */
    private String message;

    /**
     * 创建操作成功的响应对象
     *
     * @param name 文件/文件夹名称
     * @param path 文件/文件夹路径
     * @return 成功的结果对象
     */
    public static BatchOperatorResult success(String path, String name) {
        BatchOperatorResult batchOperatorResult = new BatchOperatorResult();
        batchOperatorResult.setSuccess(true);
        batchOperatorResult.setName(name);
        batchOperatorResult.setPath(path);
        return batchOperatorResult;
    }

    /**
     * 创建操作失败的响应对象
     *
     * @param name    文件/文件夹名称
     * @param path    文件/文件夹路径
     * @param message 失败的错误消息
     * @return 失败的结果对象
     */
    public static BatchOperatorResult fail(String path, String name, String message) {
        BatchOperatorResult batchOperatorResult = new BatchOperatorResult();
        batchOperatorResult.setSuccess(false);
        batchOperatorResult.setName(name);
        batchOperatorResult.setPath(path);
        batchOperatorResult.setMessage(message);
        return batchOperatorResult;
    }

}
