package im.tlgxy.cloud.module.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.LearningEmbeddingProperties;
import im.tlgxy.cloud.module.ai.infrastructure.learning.AiLearningContentExtractor;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import im.tlgxy.cloud.module.ai.service.LearningEmbeddingClient;
import im.tlgxy.cloud.module.ai.service.LearningOcrService;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import im.tlgxy.cloud.module.learning.application.port.LearningEmbeddingGateway;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.mapper.LearningCourseMapper;
import im.tlgxy.cloud.module.learning.mapper.LearningMaterialMapper;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import im.tlgxy.cloud.module.learning.model.MaterialCitation;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks real adapter registration and constructor injection without starting the application or a database. */
class LearningPortWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // Already-created singleton mocks bypass processing of legacy @Resource fields.
                // Only the real learning/AI slice below is instantiated and autowired by Spring.
                var beans = context.getBeanFactory();
                beans.registerSingleton("learningCourseMapper", mock(LearningCourseMapper.class));
                beans.registerSingleton("learningMaterialMapper", mock(LearningMaterialMapper.class));
                beans.registerSingleton("userService", mock(UserService.class));
                beans.registerSingleton("transactions", mock(TransactionTemplate.class));
                beans.registerSingleton("storageSourceService", mock(StorageSourceService.class));
                beans.registerSingleton("userStorageSourceService", mock(UserStorageSourceService.class));
                beans.registerSingleton("filterConfigService", mock(FilterConfigService.class));
                beans.registerSingleton("passwordConfigService", mock(PasswordConfigService.class));
            })
            // Register this slice explicitly: the legacy application's extra @ComponentScan
            // does not honor Boot's test-configuration exclusion filters.
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(LearningEmbeddingProperties.class, () -> {
                var settings = new LearningEmbeddingProperties();
                settings.setEnabled(false);
                return settings;
            })
            .withUserConfiguration(LearningLibraryService.class, LearningSemanticIndex.class, LearningFileReader.class,
                    LearningEmbeddingClient.class, AiLearningContentExtractor.class, AiAttachmentService.class);

    @Test
    void eachPortResolvesUniquelyAndApplicationFieldsReceiveTheRealAdapters() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(LearningFileAccess.class).size());
            assertEquals(1, context.getBeansOfType(LearningContentExtractor.class).size());
            assertEquals(1, context.getBeansOfType(LearningEmbeddingGateway.class).size());

            var reader = context.getBean(LearningFileReader.class);
            var extractor = context.getBean(AiLearningContentExtractor.class);
            var embeddings = context.getBean(LearningEmbeddingClient.class);
            assertSame(reader, context.getBean(LearningFileAccess.class));
            assertSame(extractor, context.getBean(LearningContentExtractor.class));
            assertSame(embeddings, context.getBean(LearningEmbeddingGateway.class));

            var library = context.getBean(LearningLibraryService.class);
            var semantic = context.getBean(LearningSemanticIndex.class);
            assertSame(reader, ReflectionTestUtils.getField(library, "reader"));
            assertSame(extractor, ReflectionTestUtils.getField(library, "extractor"));
            assertSame(semantic, ReflectionTestUtils.getField(library, "semantic"));
            assertSame(embeddings, ReflectionTestUtils.getField(semantic, "client"));
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            verifyNoInteractions(context.getBean(LearningCourseMapper.class), context.getBean(LearningMaterialMapper.class),
                    context.getBean(StorageSourceService.class), context.getBean(UserService.class));
        });
    }

    @Test
    void actualPlainTextAdapterWorksWithoutOcrDatabaseOrAnExternalProvider() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var extraction = context.getBean(LearningContentExtractor.class)
                    .extract("普通课程笔记".getBytes(StandardCharsets.UTF_8), "notes.md");

            assertEquals("普通课程笔记", extraction.content());
            assertEquals(6, extraction.coveredChars());
            assertNull(extraction.indexedPages());
            assertNull(extraction.totalPages());
            assertFalse(extraction.truncated());
            assertFalse(context.getBean(LearningEmbeddingGateway.class).configured());
            assertTrue(context.getBeansOfType(LearningOcrService.class).isEmpty());
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        });
    }

    @Test
    void disabledRealGatewayPreservesKeywordFallbackAndClearsOldVectors() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var settings = context.getBean(LearningEmbeddingProperties.class);
            var gateway = context.getBean(LearningEmbeddingGateway.class);
            assertFalse(settings.isEnabled());
            assertEquals("", settings.getBaseUrl());
            assertEquals("", settings.getApiKey());
            assertFalse(gateway.configured());
            assertEquals("", gateway.signature());

            var material = new LearningMaterial();
            material.setContent("课程笔记");
            material.setEmbeddingJson("old-vectors");
            material.setEmbeddingSignature("old-model");
            material.setSemanticSegments(1);
            var semantic = context.getBean(LearningSemanticIndex.class);
            semantic.prepare(material);
            assertNull(material.getEmbeddingJson());
            assertNull(material.getEmbeddingSignature());
            assertEquals(0, material.getSemanticSegments());

            var lexical = List.of(new MaterialCitation("M1-S1", 1, 1, "笔记", "local", "/notes.md", 1, "课程笔记", 1));
            var result = semantic.rank(List.of(material), "课程", lexical);
            assertEquals(lexical, result.matches());
            assertTrue(result.notice().contains("语义检索未启用"));
        });
    }

}
