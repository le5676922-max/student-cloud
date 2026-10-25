package im.tlgxy.cloud.architecture;

import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import im.tlgxy.cloud.module.learning.application.port.LearningEmbeddingGateway;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.controller.LearningController;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.learning.service.LearningSemanticIndex;
import im.tlgxy.cloud.module.storage.annotation.CheckPassword;
import im.tlgxy.cloud.module.storage.application.DirectoryListingApplicationService;
import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.controller.file.FileController;
import im.tlgxy.cloud.module.storage.model.request.base.FileItemRequest;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.*;

/** Executable boundaries for the migrated slice, not a claim that all legacy modules are decoupled. */
class BackendModuleBoundaryTest {
    @Test
    void learningApplicationDependsOnOwnedPortsInsteadOfAiImplementations() throws Exception {
        assertField(LearningLibraryService.class, "reader", LearningFileAccess.class);
        assertField(LearningLibraryService.class, "extractor", LearningContentExtractor.class);
        assertField(LearningSemanticIndex.class, "client", LearningEmbeddingGateway.class);
        for (Class<?> type : List.of(LearningLibraryService.class, LearningSemanticIndex.class)) {
            assertNoReference(type, "im/tlgxy/cloud/module/ai/");
            assertNoReference(type, "im/tlgxy/cloud/module/storage/context/StorageSourceContext");
        }
    }

    @Test
    void learningPortsDoNotLeakProviderSpringOrStorageImplementationTypes() throws Exception {
        for (Class<?> port : List.of(LearningContentExtractor.class, LearningEmbeddingGateway.class, LearningFileAccess.class)) {
            assertTrue(port.isInterface(), port.getName());
            for (String forbidden : List.of("im/tlgxy/cloud/module/ai/", "im/tlgxy/cloud/module/storage/", "org/springframework/")) {
                assertNoReference(port, forbidden);
                for (Class<?> nested : port.getDeclaredClasses()) assertNoReference(nested, forbidden);
            }
        }
    }

    @Test
    void fileHttpAdapterDoesNotOwnCacheCursorOrProviderOrchestration() throws Exception {
        for (String forbidden : List.of("im/tlgxy/cloud/module/storage/context/StorageSourceContext",
                "im/tlgxy/cloud/module/storage/service/DirectoryMetadataCache",
                "im/tlgxy/cloud/module/storage/service/DirectoryAccessGuard",
                "im/tlgxy/cloud/module/storage/service/NativeDirectoryCursorService",
                "im/tlgxy/cloud/module/storage/chain/FileChain")) {
            assertNoReference(FileController.class, forbidden);
        }
        assertNoReference(DirectoryListingApplicationService.class, "im/tlgxy/cloud/module/storage/context/StorageSourceContext");
        assertTrue(StorageDriverRegistry.class.isInterface());
    }

    @Test
    void migratedOrchestratorUsesExplicitConstructorDependencies() {
        assertTrue(DirectoryListingApplicationService.class.getDeclaredConstructors().length > 0);
        for (var field : DirectoryListingApplicationService.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            assertTrue(Modifier.isFinal(field.getModifiers()), "Application dependency must be final: " + field.getName());
        }
    }

    @Test
    void existingFileHttpContractAndPasswordCheckRemainUnchanged() throws Exception {
        assertArrayEquals(new String[]{"/api/storage"}, FileController.class.getAnnotation(RequestMapping.class).value());
        var list = FileController.class.getDeclaredMethod("list", FileListRequest.class);
        assertArrayEquals(new String[]{"/files"}, list.getAnnotation(PostMapping.class).value());
        assertEquals(AjaxJson.class, list.getReturnType());
        var sources = FileController.class.getDeclaredMethod("storageList");
        assertArrayEquals(new String[]{"/list"}, sources.getAnnotation(GetMapping.class).value());
        var item = FileController.class.getDeclaredMethod("fileItem", FileItemRequest.class);
        assertArrayEquals(new String[]{"/file/item"}, item.getAnnotation(PostMapping.class).value());
        var password = item.getAnnotation(CheckPassword.class);
        assertNotNull(password);
        assertEquals("[0].storageKey", password.storageKeyFieldExpression());
        assertEquals("[0].path", password.pathFieldExpression());
        assertFalse(password.pathIsDirectory());
    }

    @Test
    void learningHttpEntryStillUsesTheSameApplicationServiceAndBasePath() throws Exception {
        assertArrayEquals(new String[]{"/api/learning"}, LearningController.class.getAnnotation(RequestMapping.class).value());
        assertField(LearningController.class, "library", LearningLibraryService.class);
        assertArrayEquals(new String[]{"/courses"}, LearningController.class.getDeclaredMethod("courses").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/materials"}, LearningController.class.getDeclaredMethod("materials", int.class).getAnnotation(GetMapping.class).value());
    }

    private static void assertField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        assertEquals(type, owner.getDeclaredField(name).getType(), owner.getName() + "." + name);
    }

    private static void assertNoReference(Class<?> type, String forbidden) throws IOException {
        List<String> references = new ArrayList<>();
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(input, type.getName());
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                private void record(String value) { if (value != null && value.contains(forbidden)) references.add(value); }
                @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                    record(signature); record(parent);
                    if (interfaces != null) for (String implemented : interfaces) record(implemented);
                }
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    record(descriptor); record(signature);
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    record(descriptor); record(signature);
                    if (exceptions != null) for (String exception : exceptions) record(exception);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitTypeInsn(int opcode, String type) { record(type); }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) { record(owner); record(descriptor); }
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) { record(owner); record(descriptor); }
                        @Override public void visitLdcInsn(Object value) { if (value instanceof Type reference) record(reference.getDescriptor()); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                            record(descriptor);
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle) { record(handle.getOwner()); record(handle.getDesc()); }
                                else if (argument instanceof Type reference) record(reference.getDescriptor());
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(references.isEmpty(), () -> type.getName() + " crosses boundary " + forbidden + ": " + references);
    }
}
