package im.tlgxy.cloud.module.learning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.module.ai.service.LearningEmbeddingClient;
import im.tlgxy.cloud.module.learning.mapper.LearningCourseMapper;
import im.tlgxy.cloud.module.learning.mapper.LearningMaterialMapper;
import im.tlgxy.cloud.module.learning.model.LearningCourse;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No real user database/provider: mocked lexical read, actual isolated SQLite vector-filter SQL. */
class LearningSemanticSearchBudgetTest {
    final LearningCourseMapper courses = mock(LearningCourseMapper.class);
    final LearningMaterialMapper materials = mock(LearningMaterialMapper.class);
    final LearningFileReader reader = mock(LearningFileReader.class);
    final UserService users = mock(UserService.class);
    final LearningEmbeddingClient client = mock(LearningEmbeddingClient.class);
    final ObjectMapper json = new ObjectMapper();
    final LearningSemanticIndex semantic = new LearningSemanticIndex(client, json);
    final LearningLibraryService service = new LearningLibraryService(courses, materials, reader, null, users, null);
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final List<LambdaQueryWrapper<LearningMaterial>> reads = new ArrayList<>();
    Connection database;

    @BeforeEach void setup() throws Exception {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "semantic-search-budget");
        TableInfoHelper.initTableInfo(assistant, LearningMaterial.class);
        TableInfoHelper.initTableInfo(assistant, LearningCourse.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            var user = new User(); user.setId(call.getArgument(0)); user.setEnable(true); user.setRole(UserRoleEnum.USER); return user;
        });
        when(client.configured()).thenReturn(true); when(client.signature()).thenReturn("test-model-32");
        when(client.dimensions()).thenReturn(32); when(client.minSimilarity()).thenReturn(0.25);
        when(client.embed(anyList())).thenAnswer(call -> { float[] vector = new float[32]; vector[0] = 1; return List.of(vector); });
        when(reader.fingerprint(anyString(), anyString())).thenReturn("current");
        ReflectionTestUtils.setField(service, "semantic", semantic);
        database = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = database.createStatement()) {
            statement.execute("CREATE TABLE learning_material (id INTEGER PRIMARY KEY, owner_user_id INTEGER, course_id INTEGER, "
                    + "storage_key TEXT, path TEXT, fingerprint TEXT, indexed_at INTEGER, embedding_json TEXT, embedding_signature TEXT, semantic_segments INTEGER)");
        }
    }

    @AfterEach void close() throws Exception { pool.shutdownNow(); database.close(); }

    @Test void firstReadAlwaysExcludesVectorsAndSecondSelectIsOwnerScopedSqlBounded() throws Exception {
        var first = material(1); insert(first, validVectorJson()); lexicalThenSql(List.of(first));
        assertEquals(1, as(7, () -> service.search("notes", null)).citations().size());
        assertEquals(2, reads.size());
        assertFalse(reads.getFirst().getSqlSelect().contains("embedding_json"));
        assertFalse(reads.getFirst().getSqlSelect().contains("embedding_signature"));
        assertEquals(Set.of("id", "embedding_json", "embedding_signature", "semantic_segments"),
                new HashSet<>(Arrays.asList(reads.get(1).getSqlSelect().split(","))));
        String sql = reads.get(1).getSqlSegment();
        for (String required : List.of("owner_user_id", "id IN", "LENGTH(embedding_json)", "fingerprint", "indexed_at", "storage_key", "path", "course_id", "LIMIT 30"))
            assertTrue(sql.contains(required), required + " must be checked before the vector reaches JDBC");
        assertTrue(reads.get(1).getParamNameValuePairs().containsValue(1024 * 1024));
        assertTrue(reads.get(1).getParamNameValuePairs().containsValue(7));
        assertFalse(reads.get(1).getParamNameValuePairs().containsValue(first.getContent()));
        verify(client).embed(List.of("notes"));
    }

    @Test void oldOversizedBlobIsNotSelectedIntoJdbcAndLexicalStillWorksWithoutProviderCall() throws Exception {
        var first = material(1); insert(first, "x".repeat(5 * 1024 * 1024)); lexicalThenSql(List.of(first));
        var result = as(7, () -> service.search("notes", null));
        assertEquals(1, result.citations().size());
        assertNull(first.getEmbeddingJson()); assertTrue(result.notice().contains("1MiB"));
        assertTrue(result.notice().contains("重新索引")); verify(client, never()).embed(anyList());
    }

    @Test void refreshedOrRelinkedVectorCannotBePairedWithTheFirstTextSnapshot() throws Exception {
        var first = material(1); var changed = material(1); changed.setIndexedAt(new java.util.Date(11));
        insert(changed, validVectorJson()); lexicalThenSql(List.of(first));
        assertEquals(1, as(7, () -> service.search("notes", null)).citations().size());
        assertNull(first.getEmbeddingJson()); verify(client, never()).embed(anyList());
        try (var statement = database.createStatement()) { statement.execute("UPDATE learning_material SET indexed_at=10, path='/moved.txt'"); }
        reads.clear();
        assertEquals(1, as(7, () -> service.search("notes", null)).citations().size());
        assertNull(first.getEmbeddingJson()); verify(client, never()).embed(anyList());
    }

    @Test void secondQueryCannotLoadAnotherOwnerOrMaterialOutsideTheFirstThirtyIds() throws Exception {
        var first = material(1); var otherOwner = material(1); otherOwner.setOwnerUserId(8);
        insert(otherOwner, validVectorJson()); insert(material(99), validVectorJson()); lexicalThenSql(List.of(first));
        assertEquals(1, as(7, () -> service.search("notes", null)).citations().size());
        assertNull(first.getEmbeddingJson()); verify(client, never()).embed(anyList());
    }

    @Test void liveGrantAndWholeFileFingerprintStillRejectOtherwiseValidSemanticAndLexicalMatches() throws Exception {
        var first = material(1); insert(first, validVectorJson()); lexicalThenSql(List.of(first));
        when(reader.fingerprint("local", "/1.txt")).thenThrow(new BizException("revoked"));
        var result = as(7, () -> service.search("notes", null));
        assertTrue(result.citations().isEmpty()); assertEquals(1, result.skipped());
        doReturn("changed").when(reader).fingerprint("local", "/1.txt");
        assertTrue(as(7, () -> service.search("notes", null)).citations().isEmpty());
        verify(reader, times(2)).fingerprint("local", "/1.txt");
        assertEquals(2, slots().availablePermits());
    }

    @Test void disabledSemanticConfigurationStillNeverReadsHistoricalVectors() {
        when(client.configured()).thenReturn(false);
        when(materials.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<LearningMaterial> wrapper = call.getArgument(0);
            assertFalse(wrapper.getSqlSelect().contains("embedding_json"));
            assertFalse(wrapper.getSqlSelect().contains("embedding_signature"));
            return List.of(material(1));
        });
        assertEquals(1, as(7, () -> service.search("notes", null)).citations().size());
        verify(materials, times(1)).selectList(any()); verify(client, never()).embed(anyList());
    }

    @Test void fullSearchGateRejectsBeforeAuthenticationDbAndReleasesOnAuthenticationOrReadFailure() {
        assertTrue(slots().tryAcquire(2));
        try { assertThrows(BizException.class, () -> as(7, () -> service.search("notes", 1))); }
        finally { slots().release(2); }
        verifyNoInteractions(users, courses, materials, reader, client);
        when(users.requireLoginUser(7)).thenThrow(new BizException("disabled"));
        assertThrows(BizException.class, () -> as(7, () -> service.search("notes", null)));
        assertEquals(2, slots().availablePermits());
        doAnswer(call -> { var user = new User(); user.setEnable(true); user.setRole(UserRoleEnum.USER); return user; })
                .when(users).requireLoginUser(7);
        when(materials.selectList(any())).thenThrow(new IllegalStateException("db unavailable"));
        assertThrows(IllegalStateException.class, () -> as(7, () -> service.search("notes", null)));
        assertEquals(2, slots().availablePermits());
    }

    @Test void twoSearchesHoldSlotsThroughLiveAuthorizationAndThirdDoesNotQueueOrReadDb() throws Exception {
        when(client.configured()).thenReturn(false);
        when(materials.selectList(any())).thenAnswer(call -> List.of(material(1)));
        var checking = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(reader.fingerprint(anyString(), anyString())).thenAnswer(call -> {
            checking.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); return "current";
        });
        var first = pool.submit(() -> as(7, () -> service.search("notes", null)));
        var second = pool.submit(() -> as(8, () -> service.search("notes", null)));
        try {
            assertTrue(checking.await(2, TimeUnit.SECONDS)); assertEquals(0, slots().availablePermits());
            assertThrows(BizException.class, () -> as(9, () -> service.search("notes", null)));
            verify(users, never()).requireLoginUser(9); verify(materials, times(2)).selectList(any());
        } finally { release.countDown(); }
        assertEquals(1, first.get(2, TimeUnit.SECONDS).citations().size());
        assertEquals(1, second.get(2, TimeUnit.SECONDS).citations().size());
        assertEquals(2, slots().availablePermits());
    }

    @Test void vectorSqlFailureDoesNotLeakOneOfTheTwoSearchSlots() {
        when(materials.selectList(any())).thenReturn(List.of(material(1))).thenThrow(new IllegalStateException("vector db unavailable"));
        assertThrows(IllegalStateException.class, () -> as(7, () -> service.search("notes", null)));
        assertEquals(2, slots().availablePermits());
    }

    private void lexicalThenSql(List<LearningMaterial> lexical) {
        when(materials.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<LearningMaterial> wrapper = call.getArgument(0); reads.add(wrapper);
            return wrapper.getSqlSelect().contains("content") ? lexical : executeVectorRead(wrapper);
        });
    }

    private List<LearningMaterial> executeVectorRead(LambdaQueryWrapper<LearningMaterial> wrapper) throws SQLException {
        String sql = "SELECT " + wrapper.getSqlSelect() + " FROM learning_material " + wrapper.getCustomSqlSegment();
        var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(MPGENVAL[0-9]+)\\}").matcher(sql);
        List<Object> parameters = new ArrayList<>(); StringBuilder prepared = new StringBuilder();
        while (matcher.find()) { parameters.add(wrapper.getParamNameValuePairs().get(matcher.group(1))); matcher.appendReplacement(prepared, "?"); }
        matcher.appendTail(prepared);
        List<LearningMaterial> vectors = new ArrayList<>();
        try (var statement = database.prepareStatement(prepared.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                var parameter = parameters.get(i);
                if (parameter instanceof java.util.Date date) statement.setLong(i + 1, date.getTime());
                else statement.setObject(i + 1, parameter);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var vector = new LearningMaterial(); vector.setId(rows.getInt("id"));
                    vector.setEmbeddingJson(rows.getString("embedding_json")); vector.setEmbeddingSignature(rows.getString("embedding_signature"));
                    vector.setSemanticSegments(rows.getInt("semantic_segments")); vectors.add(vector);
                }
            }
        }
        return vectors;
    }

    private void insert(LearningMaterial material, String vector) throws SQLException {
        try (var statement = database.prepareStatement("INSERT INTO learning_material VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            statement.setInt(1, material.getId()); statement.setInt(2, material.getOwnerUserId()); statement.setInt(3, material.getCourseId());
            statement.setString(4, material.getStorageKey()); statement.setString(5, material.getPath()); statement.setString(6, material.getFingerprint());
            statement.setLong(7, material.getIndexedAt().getTime()); statement.setString(8, vector); statement.setString(9, "test-model-32");
            statement.setInt(10, 1); statement.executeUpdate();
        }
    }

    private String validVectorJson() throws Exception { float[] value = new float[32]; value[0] = 1; return json.writeValueAsString(List.of(value)); }
    private Semaphore slots() { return (Semaphore) ReflectionTestUtils.getField(service, "searchSlots"); }
    private static LearningMaterial material(int id) {
        var value = new LearningMaterial(); value.setId(id); value.setOwnerUserId(7); value.setCourseId(1); value.setStorageKey("local");
        value.setPath("/" + id + ".txt"); value.setName("notes.txt"); value.setTags(""); value.setContent("private notes");
        value.setFingerprint("current"); value.setIndexedAt(new java.util.Date(10)); value.setSemanticSegments(1); return value;
    }
    private static <T> T as(int owner, Supplier<T> action) {
        var value = new AtomicReference<T>(); BackgroundActorContext.runAs(owner, () -> value.set(action.get())); return value.get();
    }
}
