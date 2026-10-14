package im.tlgxy.cloud.module.storage.context;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class StorageSourceMutationGuardTest {
    @Test void guardIsHeldThroughTransactionCompletionAndRejectsParallelMutationsWithoutWaiting() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (var first = StorageSourceMutationGuard.acquire(); var nested = StorageSourceMutationGuard.acquire()) { }
            assertTrue(CompletableFuture.supplyAsync(() -> {
                try (var second = StorageSourceMutationGuard.acquire()) { return false; }
                catch (BizException expected) { return true; }
            }).join());
        } finally {
            TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertDoesNotThrow(() -> { try (var after = StorageSourceMutationGuard.acquire()) { } });
    }
    @Test void directNestedCallsDoNotAcquireAdditionalPermitsOrLeakTheGuard() {
        try (var first = StorageSourceMutationGuard.acquire(); var nested = StorageSourceMutationGuard.acquire()) { }
        assertTrue(CompletableFuture.supplyAsync(() -> { try (var next = StorageSourceMutationGuard.acquire()) { return true; } }).join());
    }
}
