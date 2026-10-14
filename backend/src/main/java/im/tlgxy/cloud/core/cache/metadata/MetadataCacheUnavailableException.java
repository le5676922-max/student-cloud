package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.core.exception.core.BizException;

/** Do not claim a write was successful while other instances can still see the previous generation. */
public final class MetadataCacheUnavailableException extends BizException {
    public MetadataCacheUnavailableException(boolean databaseCommitted) {
        super(databaseCommitted
                ? "数据已保存，但存储列表缓存刷新失败。请恢复 Redis 后刷新页面，不要重复提交。"
                : "Redis 暂不可用，为避免存储列表缓存不一致，本次修改未执行。请稍后重试。");
    }
}
