package im.tlgxy.cloud.module.storage.context;

import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import im.tlgxy.cloud.module.storage.support.StorageSourceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageSourceContextCommitTest {
    private final Map<?, ?> previousBeans = (Map<?, ?>) ReflectionTestUtils.getField(StorageSourceContext.class, "storageTypeServiceNameMap");
    private final S3ServiceImpl prototype = mock(S3ServiceImpl.class);
    private final S3ServiceImpl old = mock(S3ServiceImpl.class);
    private final S3ServiceImpl next = mock(S3ServiceImpl.class);
    private final MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class);
    private final MockedStatic<StorageSourceSupport> support = mockStatic(StorageSourceSupport.class);
    private final List<S3ServiceImpl> beans = new ArrayList<>(List.of(old, next));

    StorageSourceContextCommitTest() {
        when(prototype.getStorageTypeEnum()).thenReturn(StorageTypeEnum.S3);
        StorageSourceContext.load(Map.of("s3", prototype));
        spring.when(() -> SpringUtil.getBean(S3ServiceImpl.class)).thenAnswer(call -> beans.removeFirst());
        support.when(() -> StorageSourceSupport.getStorageSourceParamList(any())).thenReturn(List.of());
    }
    @AfterEach void restore() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) complete(false);
        StorageSourceContext.destroy(source("new-key"));
        ReflectionTestUtils.setField(StorageSourceContext.class, "storageTypeServiceNameMap", previousBeans);
        support.close(); spring.close();
    }
    private StorageSource source(String key) {
        StorageSource source = new StorageSource(); source.setId(900042); source.setKey(key); source.setName("Synthetic"); source.setType(StorageTypeEnum.S3); return source;
    }
    private StorageSourceInitDTO dto(String key) { return StorageSourceInitDTO.convert(source(key), List.of()); }
    private void begin() { TransactionSynchronizationManager.initSynchronization(); }
    private void complete(boolean commit) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        if (commit) callbacks.forEach(TransactionSynchronization::afterCommit);
        callbacks.forEach(callback -> callback.afterCompletion(commit ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
    }
    @Test void oldContextRemainsAvailableUntilCommitThenBothIdAndKeyAtomicallySwitch() {
        StorageSourceContext.init(dto("old-key")); begin(); StorageSourceContext.init(dto("new-key"));
        assertSame(old, StorageSourceContext.getByStorageId(900042)); verify(old, never()).destroy();
        assertNull(StorageSourceContext.getByStorageKey("new-key")); complete(true);
        assertSame(next, StorageSourceContext.getByStorageId(900042)); assertSame(next, StorageSourceContext.getByStorageKey("new-key"));
        assertNull(StorageSourceContext.getByStorageKey("old-key")); verify(old).destroy(); verify(next, never()).destroy();
    }
    @Test void rollbackClosesOnlyCandidateAndNeverChangesOldKeyOrContext() {
        StorageSourceContext.init(dto("old-key")); begin(); StorageSourceContext.init(dto("new-key")); complete(false);
        assertSame(old, StorageSourceContext.getByStorageId(900042)); assertSame(old, StorageSourceContext.getByStorageKey("old-key"));
        assertNull(StorageSourceContext.getByStorageKey("new-key")); verify(next).destroy(); verify(old, never()).destroy();
    }
    @Test void candidateValidationFailureClosesCandidateAndKeepsOldContext() {
        StorageSourceContext.init(dto("old-key")); doThrow(new IllegalStateException("synthetic failure")).when(next).testConnection();
        assertThrows(IllegalStateException.class, () -> StorageSourceContext.init(dto("new-key")));
        assertSame(old, StorageSourceContext.getByStorageId(900042)); verify(old, never()).destroy(); verify(next).destroy();
    }
    @Test void rollbackOfDeleteKeepsActiveConnectionAndCommitDeletesIt() {
        StorageSourceContext.init(dto("old-key")); begin(); StorageSourceContext.destroy(source("old-key")); complete(false);
        assertSame(old, StorageSourceContext.getByStorageId(900042)); verify(old, never()).destroy();
        begin(); StorageSourceContext.destroy(source("old-key")); complete(true); verify(old).destroy();
        assertNull(StorageSourceContext.getByStorageKey("old-key"));
    }
}
