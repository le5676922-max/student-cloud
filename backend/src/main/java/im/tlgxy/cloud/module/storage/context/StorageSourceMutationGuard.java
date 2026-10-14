package im.tlgxy.cloud.module.storage.context;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Semaphore;

/** Serializes administrative context changes through transaction completion, never waits indefinitely. */
public final class StorageSourceMutationGuard implements AutoCloseable {
    private static final Semaphore MUTATION = new Semaphore(1);
    private static final Object RESOURCE = new Object();
    private static final ThreadLocal<Boolean> DIRECT_OWNER = new ThreadLocal<>();
    private final boolean releaseOnClose;

    private StorageSourceMutationGuard(boolean releaseOnClose) { this.releaseOnClose = releaseOnClose; }

    public static StorageSourceMutationGuard acquire() {
        if (TransactionSynchronizationManager.hasResource(RESOURCE) || Boolean.TRUE.equals(DIRECT_OWNER.get())) return new StorageSourceMutationGuard(false);
        if (!MUTATION.tryAcquire()) throw new BizException("已有存储配置操作正在进行，请稍后刷新后重试");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            DIRECT_OWNER.set(Boolean.TRUE);
            return new StorageSourceMutationGuard(true);
        }
        TransactionSynchronizationManager.bindResource(RESOURCE, Boolean.TRUE);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
                    MUTATION.release();
                }
            });
        } catch (RuntimeException exception) {
            TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
            MUTATION.release();
            throw exception;
        }
        return new StorageSourceMutationGuard(false);
    }

    @Override public void close() {
        if (releaseOnClose) {
            DIRECT_OWNER.remove();
            MUTATION.release();
        }
    }
}
