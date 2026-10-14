package im.tlgxy.cloud.module.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.service.LearningEmbeddingClient;
import im.tlgxy.cloud.module.learning.model.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LearningSemanticIndexTest {
    final LearningEmbeddingClient client = mock(LearningEmbeddingClient.class);
    final ObjectMapper json = new ObjectMapper();
    final LearningSemanticIndex semantic = new LearningSemanticIndex(client, json);

    @BeforeEach void configure() {
        when(client.configured()).thenReturn(true);
        when(client.signature()).thenReturn("embedding-v1:test-provider-model-32");
        when(client.dimensions()).thenReturn(32);
        when(client.minSimilarity()).thenReturn(0.25);
        when(client.embed(anyList())).thenAnswer(call -> {
            List<String> segments = call.getArgument(0);
            return segments.stream().map(ignored -> vector()).toList();
        });
    }

    @Test void indexingIsBoundedAndExplainsUncoveredText() {
        var material = material(1, "x".repeat(240_000));
        semantic.prepare(material);
        assertEquals(64, material.getSemanticSegments());
        assertTrue(material.getSemanticNotice().contains("后续文字"));
        verify(client).embed(argThat(input -> input.size() == 64 && input.stream().allMatch(text -> text.length() <= 700)));
        assertNotNull(material.getEmbeddingJson());
        assertEquals(client.signature(), material.getEmbeddingSignature());
    }

    @Test void highDimensionIndexReducesCoverageBeforeSendingPrivateSegments() {
        when(client.dimensions()).thenReturn(4096);
        when(client.embed(anyList())).thenAnswer(call -> {
            List<String> segments = call.getArgument(0);
            return segments.stream().map(ignored -> { float[] value = new float[4096]; value[0] = 1; return value; }).toList();
        });
        var material = material(1, "x".repeat(240_000));
        semantic.prepare(material);
        int expected = (LearningSemanticIndex.MAX_JSON_CHARS - 2) / (4096 * 17 + 3);
        assertEquals(expected, material.getSemanticSegments());
        assertTrue(expected < 64);
        assertTrue(material.getEmbeddingJson().length() <= 1024 * 1024);
        assertTrue(material.getSemanticNotice().contains("前 " + expected + " 个文字片段"));
        assertTrue(material.getSemanticNotice().contains("后续文字"));
        verify(client).embed(argThat(input -> input.size() == expected));
        assertTrue(semantic.rank(List.of(material), "question", List.of()).notice().contains("前 " + expected + " 段"));
    }

    @Test void finalEncodingLimitStillRejectsUnexpectedOversizeWithoutPersistingVectors() throws Exception {
        var encoder = mock(ObjectMapper.class);
        when(encoder.writeValueAsString(any())).thenReturn("x".repeat(LearningSemanticIndex.MAX_JSON_CHARS + 1));
        var material = material(1, "notes");
        new LearningSemanticIndex(client, encoder).prepare(material);
        assertNull(material.getEmbeddingJson()); assertNull(material.getEmbeddingSignature());
        assertEquals(0, material.getSemanticSegments());
        assertTrue(material.getSemanticNotice().contains("重新索引"));
    }

    @Test void oversizedOrUnloadedHistoricalIndexKeepsLexicalAndNeverUploadsDocumentsOrQuestion() {
        var material = material(1, "private notes");
        material.setEmbeddingSignature(client.signature()); material.setSemanticSegments(64);
        material.setEmbeddingJson("x".repeat(LearningSemanticIndex.MAX_JSON_CHARS + 1));
        var lexical = List.of(citation(material));
        var oversized = semantic.rank(List.of(material), "notes", lexical);
        assertEquals(lexical, oversized.matches());
        assertTrue(oversized.notice().contains("1MiB")); assertTrue(oversized.notice().contains("重新索引"));
        material.setEmbeddingJson(null); material.setEmbeddingSignature(null);
        var unloaded = semantic.rank(List.of(material), "notes", lexical);
        assertEquals(lexical, unloaded.matches()); assertTrue(unloaded.notice().contains("已变化"));
        verify(client, never()).embed(anyList());
    }

    @Test void disabledOrFailedRefreshClearsStaleVectorsWithoutProviderDetails() {
        var material = material(1, "notes");
        material.setEmbeddingJson("old"); material.setEmbeddingSignature("old");
        when(client.configured()).thenReturn(false);
        semantic.prepare(material);
        assertNull(material.getEmbeddingJson()); assertNull(material.getEmbeddingSignature());
        verify(client, never()).embed(anyList());
        when(client.configured()).thenReturn(true);
        when(client.embed(anyList())).thenThrow(new RuntimeException("synthetic-private-response"));
        semantic.prepare(material);
        assertNull(material.getEmbeddingJson()); assertEquals(0, material.getSemanticSegments());
        assertFalse(material.getSemanticNotice().contains("synthetic-private"));
    }

    @Test void queryCanFindAParaphraseWithoutUploadingTheDocumentAgain() {
        var material = material(1, "成本低的模型可以减少预算");
        semantic.prepare(material); clearInvocations(client);
        var result = semantic.rank(List.of(material), "如何省钱", List.of());
        assertEquals(1, result.matches().size());
        assertEquals("M1-S1", result.matches().getFirst().reference());
        verify(client).embed(List.of("如何省钱"));
        verify(client, times(1)).embed(anyList());
        assertTrue(result.notice().contains("分数不是准确率"));
    }

    @Test void modelChangeAndMissingIndexesUseLexicalWithoutAProviderRequest() {
        var material = material(1, "notes"); semantic.prepare(material); clearInvocations(client);
        when(client.signature()).thenReturn("different-provider");
        var lexical = List.of(citation(material));
        assertEquals(lexical, semantic.rank(List.of(material), "notes", lexical).matches());
        verify(client, never()).embed(anyList());
    }

    @Test void malformedMaterialDoesNotLeakItsEarlierValidVectorCandidate() throws Exception {
        var material = material(1, "x".repeat(1_200));
        material.setEmbeddingSignature(client.signature()); material.setSemanticSegments(2);
        material.setEmbeddingJson(json.writeValueAsString(new float[][]{vector(), new float[31]}));
        var result = semantic.rank(List.of(material), "question", List.of());
        assertTrue(result.matches().isEmpty()); assertTrue(result.notice().contains("部分语义索引无效"));
    }

    @Test void providerFailureUsesExactlyTheExistingLexicalResults() {
        var material = material(1, "notes"); semantic.prepare(material);
        var lexical = List.of(citation(material));
        when(client.embed(anyList())).thenThrow(new RuntimeException("private upstream body"));
        var result = semantic.rank(List.of(material), "notes", lexical);
        assertEquals(lexical, result.matches()); assertTrue(result.notice().contains("退回关键词"));
        assertFalse(result.notice().contains("upstream"));
    }

    @Test void semanticCitationsStillFailClosedOnChangedFileOrRevokedPermission() {
        var material = material(1, "知识材料"); semantic.prepare(material);
        var reader = mock(LearningFileReader.class);
        var library = new LearningLibraryService(null, null, reader, null, null, null);
        ReflectionTestUtils.setField(library, "semantic", semantic);
        when(reader.fingerprint("local", "/1.txt")).thenReturn("changed");
        assertTrue(library.searchCandidates(List.of(material), Set.of("不存在"), "同义问题").citations().isEmpty());
        when(reader.fingerprint("local", "/1.txt")).thenThrow(new BizException("revoked"));
        assertTrue(library.searchCandidates(List.of(material), Set.of("不存在"), "同义问题").citations().isEmpty());
        verify(reader, times(2)).fingerprint("local", "/1.txt");
    }

    @Test void vectorAndProviderSignatureNeverAppearInSerializedMaterial() throws Exception {
        var material = material(1, "private text"); semantic.prepare(material);
        var body = json.readTree(json.writeValueAsString(material));
        assertFalse(body.has("content")); assertFalse(body.has("embeddingJson"));
        assertFalse(body.has("embeddingSignature")); assertFalse(body.has("fingerprint"));
        assertEquals(1, body.path("semanticSegments").asInt());
    }

    private static float[] vector() { float[] vector = new float[32]; vector[0] = 1; return vector; }
    private static LearningMaterial material(int id, String content) {
        var material = new LearningMaterial();
        material.setId(id); material.setOwnerUserId(7); material.setCourseId(1); material.setStorageKey("local");
        material.setPath("/" + id + ".txt"); material.setName(id + ".txt"); material.setTags("");
        material.setContent(content); material.setFingerprint("current"); return material;
    }
    private static MaterialCitation citation(LearningMaterial material) {
        return new MaterialCitation("M1-S1", material.getId(), material.getCourseId(), material.getName(),
                material.getStorageKey(), material.getPath(), 1, material.getContent(), 1);
    }
}
