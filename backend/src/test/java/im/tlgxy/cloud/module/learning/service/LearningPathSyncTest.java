package im.tlgxy.cloud.module.learning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.mapper.*;
import im.tlgxy.cloud.module.learning.model.*;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningPathSyncTest {
    final LearningCourseMapper courses = mock(LearningCourseMapper.class);
    final LearningMaterialMapper materials = mock(LearningMaterialMapper.class);
    final LearningFileAccess reader = mock(LearningFileAccess.class);
    final UserService users = mock(UserService.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final LearningLibraryService service = new LearningLibraryService(courses, materials, reader,
            (bytes, name) -> {
                String content = new String(bytes, StandardCharsets.UTF_8);
                return new LearningContentExtractor.Extraction(content, content.length(), null, null, false, "已索引");
            }, users, transactions);
    final byte[] bytes = "test notes".getBytes(StandardCharsets.UTF_8);
    final String hash = LearningLibraryService.fingerprint(bytes);

    @BeforeEach void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "path-sync");
        TableInfoHelper.initTableInfo(assistant, LearningMaterial.class);
        TableInfoHelper.initTableInfo(assistant, LearningCourse.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User user = new User(); user.setId(call.getArgument(0)); user.setRole(UserRoleEnum.USER); user.setEnable(true); return user;
        });
        when(transactions.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(materials.selectCount(any())).thenReturn(0L);
        when(materials.update(isNull(), any())).thenReturn(1);
        when(reader.fingerprint(anyString(), anyString())).thenReturn(hash);
        when(reader.read(anyString(), anyString())).thenReturn(bytes);
        when(courses.selectOne(any(Wrapper.class))).thenReturn(new LearningCourse());
    }

    @Test void folderRelocationUsesSegmentBoundaryAndDoesNotReadOrWriteOtherOwners() {
        when(materials.selectList(any())).thenReturn(List.of(material(1, "/notes/a.txt"), material(2, "/notes-old/b.txt")));
        var plan = as(7, () -> service.preparePathChange("local", "/notes", "/archive", true));
        assertEquals(7, plan.owner()); assertEquals(1, plan.changes().size());
        assertEquals("/archive/a.txt", plan.changes().getFirst().newPath());
        verify(materials).selectList(argThat(wrapper -> wrapper.getSqlSegment().contains("owner_user_id")
                && wrapper.getSqlSegment().contains("storage_key")));
        verifyNoInteractions(reader, transactions);
    }

    @Test void duplicateCourseAssociationIsRejectedBeforeFilesystemMutation() {
        when(materials.selectList(any())).thenReturn(List.of(material(1, "/a.txt"), material(2, "/b.txt")));
        assertThrows(BizException.class, () -> as(7, () -> service.preparePathChange("local", "/a.txt", "/b.txt", false)));
        verifyNoInteractions(reader, transactions);
    }

    @Test void moveUpdatesPathAndNameButDoesNotRewriteContentOrGrantAccess() {
        var indexed = material(1, "/a.txt");
        when(materials.selectList(any())).thenReturn(List.of(indexed));
        when(materials.selectOne(any(Wrapper.class))).thenReturn(indexed);
        var plan = as(7, () -> service.preparePathChange("local", "/a.txt", "/docs/b.txt", false));
        as(7, () -> { service.completePathChange(plan); return null; });
        verify(reader).fingerprint("local", "/docs/b.txt");
        verify(materials).update(isNull(), argThat(wrapper -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LearningMaterial>) wrapper;
            return update.getSqlSet().contains("path=") && update.getSqlSet().contains("name=")
                    && !update.getSqlSet().contains("content=") && update.getSqlSegment().contains("fingerprint");
        }));
    }

    @Test void changedFileOrRevokedReadDoesNotBlessAnOldIndexAndReturnsNoReplayError() {
        var plan = new LearningLibraryService.PathChangePlan(7, "local",
                List.of(new LearningLibraryService.PathChange(1, 1, "/a.txt", "/b.txt", hash, new Date(10))));
        when(reader.fingerprint(anyString(), anyString())).thenReturn("modified");
        assertThrows(LearningPathSyncException.class, () -> as(7, () -> { service.completePathChange(plan); return null; }));
        verifyNoInteractions(transactions);
        when(reader.fingerprint(anyString(), anyString())).thenThrow(new BizException("denied"));
        assertThrows(LearningPathSyncException.class, () -> as(7, () -> { service.completePathChange(plan); return null; }));
    }

    @Test void compareAndSetConflictIsUncertainRatherThanOrdinaryRetryableFailure() {
        var indexed = material(1, "/a.txt");
        when(materials.selectList(any())).thenReturn(List.of(indexed));
        when(materials.selectOne(any(Wrapper.class))).thenReturn(indexed);
        when(materials.update(isNull(), any())).thenReturn(0);
        var plan = as(7, () -> service.preparePathChange("local", "/a.txt", "/b.txt", false));
        assertThrows(LearningPathSyncException.class, () -> as(7, () -> { service.completePathChange(plan); return null; }));
    }

    @Test void deletedIndexIsNeverResurrectedAndChangedActorCannotApplyPlan() {
        var indexed = material(1, "/a.txt");
        when(materials.selectList(any())).thenReturn(List.of(indexed));
        var plan = as(7, () -> service.preparePathChange("local", "/a.txt", "/b.txt", false));
        as(7, () -> { service.completePathChange(plan); return null; });
        verify(materials, never()).update(any(), any());
        assertThrows(LearningPathSyncException.class, () -> as(8, () -> { service.completePathChange(plan); return null; }));
    }

    @Test void relinkValidatesAndReindexesNewFileWithoutAnyFilesystemWrite() {
        when(materials.selectOne(any(Wrapper.class))).thenAnswer(call -> material(1, "/missing.txt"));
        var repaired = as(7, () -> service.relink(1, new LearningRequests.Relink("local", "/docs/found.txt")));
        assertEquals("/docs/found.txt", repaired.getPath()); assertEquals("found.txt", repaired.getName());
        assertEquals("test notes", repaired.getContent());
        verify(materials).updateById(repaired);
        verify(reader).read("local", "/docs/found.txt");
        verify(reader).fingerprint("local", "/docs/found.txt");
    }

    @Test void relinkOfUnownedOrProtectedFileCannotChangeRecord() {
        assertThrows(BizException.class, () -> as(7, () -> service.relink(1, new LearningRequests.Relink("local", "/secret.txt"))));
        when(materials.selectOne(any(Wrapper.class))).thenReturn(material(1, "/missing.txt"));
        when(reader.read(anyString(), anyString())).thenThrow(new BizException("protected"));
        assertThrows(BizException.class, () -> as(7, () -> service.relink(1, new LearningRequests.Relink("local", "/secret.txt"))));
        verify(materials, never()).updateById(any());
    }

    LearningMaterial material(int id, String path) {
        var value = new LearningMaterial(); value.setId(id); value.setOwnerUserId(7); value.setCourseId(1);
        value.setStorageKey("local"); value.setPath(path); value.setFingerprint(hash); value.setIndexedAt(new Date(10)); return value;
    }
    static <T> T as(int user, Supplier<T> work) {
        var result = new AtomicReference<T>(); BackgroundActorContext.runAs(user, () -> result.set(work.get())); return result.get();
    }
}
