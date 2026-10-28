package io.studentcloud.content.learning;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private references and extracted text. No physical paths, remote extractors, or shared indexes. */
public final class LearningLibraryService {
    public static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SEARCH_DOCUMENTS = 200;
    private static final long MAX_SEARCH_BYTES = 50L * 1024 * 1024;
    private static final int MAX_QUERY_TERMS = 32;
    private static final int PASSAGE_CHARS = 500;
    private static final int PASSAGE_STEP = 250;
    private static final Pattern QUERY_WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]{2,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "如何", "怎么", "什么", "哪些", "请问", "解释", "请解", "可以", "一个", "这个", "那个", "有关", "关于",
            "为何", "为什", "怎样", "一下", "是什", "么是", "有哪",
            "what", "when", "where", "which", "who", "why", "how", "does", "did", "the", "is", "are", "was", "were",
            "and", "or", "of", "to", "in", "on", "at", "by", "for", "from", "with", "can", "could", "would", "should",
            "this", "that", "these", "those", "it", "its", "me", "my", "we", "our", "you", "your", "please", "explain");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final DriveOperations mounts;
    private final Semaphore parsing = new Semaphore(2);

    public LearningLibraryService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                                  AccountDirectory accounts, DriveOperations mounts) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.clock = clock;
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.accounts = accounts; this.mounts = mounts;
    }

    public record Course(long id, String name, String semester, long createdAt, long updatedAt) { }
    public record Material(long id, long courseId, String storageKey, String path, String name, String tags,
                           int indexVersion, int coveredChars, boolean truncated, Integer indexedPages,
                           Integer totalPages, String semanticNotice, String indexedAt, long createdAt, long updatedAt) { }
    public record Citation(String reference, String name, int segment, String excerpt, long materialId,
                           long courseId, String storageKey, String path) { }
    public record SearchResult(List<Citation> citations, int skipped, boolean truncated) { }

    public List<Course> listCourses(Actor actor) {
        Actor current = accounts.current(actor);
        return jdbc.query("SELECT * FROM sc_learning_course WHERE owner_id=? ORDER BY updated_at DESC,id DESC", this::course, current.id());
    }

    public Course createCourse(Actor actor, String name, String semester) {
        String validName = text(name, 120, false), validSemester = text(semester, 80, true);
        return tx.execute(status -> {
            Actor current = accounts.current(actor); lockOwner(current.id());
            if (count("SELECT COUNT(*) FROM sc_learning_course WHERE owner_id=?", current.id()) >= 50) throw invalid("最多创建 50 门个人课程");
            long now = clock.millis();
            long id = insert("INSERT INTO sc_learning_course(owner_id,name,semester,created_at,updated_at) VALUES(?,?,?,?,?)",
                    current.id(), validName, validSemester, now, now);
            return requireCourse(current, id, false);
        });
    }

    public Course updateCourse(Actor actor, long courseId, String name, String semester) {
        String validName = text(name, 120, false), validSemester = text(semester, 80, true);
        return tx.execute(status -> {
            requireCourse(actor, courseId, true);
            jdbc.update("UPDATE sc_learning_course SET name=?,semester=?,updated_at=? WHERE id=?", validName, validSemester, clock.millis(), courseId);
            return requireCourse(actor, courseId, false);
        });
    }

    public void deleteCourse(Actor actor, long courseId) {
        tx.executeWithoutResult(status -> { requireCourse(actor, courseId, true); jdbc.update("DELETE FROM sc_learning_course WHERE id=?", courseId); });
    }

    public List<Material> listMaterials(Actor actor, long courseId) {
        requireCourse(actor, courseId, false);
        return jdbc.query("SELECT * FROM sc_learning_material WHERE course_id=? ORDER BY id DESC", this::material, courseId);
    }

    public Material addMaterial(Actor actor, long courseId, String storageKey, String path, String tags) {
        requireCourse(actor, courseId, false);
        String key = text(storageKey, 100, false), validTags = text(tags, 500, true);
        DrivePath drivePath = path(path);
        Snapshot snapshot = snapshot(actor, key, drivePath, true);
        return tx.execute(status -> {
            requireCourse(actor, courseId, true);
            if (count("SELECT COUNT(*) FROM sc_learning_material WHERE course_id=?", courseId) >= 200) throw invalid("每门课程最多归档 200 份资料");
            if (count("SELECT COUNT(*) FROM sc_learning_material WHERE course_id=? AND storage_key=? AND path=?", courseId, key, drivePath.value()) > 0) throw invalid("此文件已归档，请刷新索引");
            long now = clock.millis();
            var extracted = snapshot.extracted();
            long id = insert("INSERT INTO sc_learning_material(course_id,storage_key,path,name,tags,content_hash,indexed_text,covered_chars,truncated,indexed_pages,total_pages,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    courseId, key, drivePath.value(), drivePath.name(), validTags, snapshot.hash(), extracted.text(), extracted.text().length(), extracted.truncated(), extracted.indexedPages(), extracted.totalPages(), now, now);
            return row(actor, id).view();
        });
    }

    public Material refresh(Actor actor, long materialId) {
        Row existing = row(actor, materialId);
        return relink(actor, materialId, existing.view().storageKey(), existing.view().path());
    }

    public Material relink(Actor actor, long materialId, String storageKey, String path) {
        Row existing = row(actor, materialId);
        String key = text(storageKey, 100, false); DrivePath drivePath = path(path);
        Snapshot snapshot = snapshot(actor, key, drivePath, true);
        return tx.execute(status -> {
            requireCourse(actor, existing.view().courseId(), true);
            Row current = row(actor, materialId);
            if (!current.hash().equals(existing.hash()) || !current.view().path().equals(existing.view().path())) throw invalid("资料已更新，请重试");
            if (count("SELECT COUNT(*) FROM sc_learning_material WHERE course_id=? AND storage_key=? AND path=? AND id<>?", current.view().courseId(), key, drivePath.value(), materialId) > 0) throw invalid("此文件已归档");
            var extracted = snapshot.extracted();
            jdbc.update("UPDATE sc_learning_material SET storage_key=?,path=?,name=?,content_hash=?,indexed_text=?,covered_chars=?,truncated=?,indexed_pages=?,total_pages=?,updated_at=? WHERE id=?",
                    key, drivePath.value(), drivePath.name(), snapshot.hash(), extracted.text(), extracted.text().length(), extracted.truncated(), extracted.indexedPages(), extracted.totalPages(), clock.millis(), materialId);
            return row(actor, materialId).view();
        });
    }

    public void deleteMaterial(Actor actor, long materialId) {
        tx.executeWithoutResult(status -> {
            Row existing = row(actor, materialId); requireCourse(actor, existing.view().courseId(), true);
            jdbc.update("DELETE FROM sc_learning_material WHERE id=?", materialId);
        });
    }

    /** Every candidate is freshly read through MountService and hashed; stale snippets are never returned. */
    public SearchResult search(Actor actor, Long courseId, String query, int limit) {
        Actor current = accounts.current(actor);
        Query terms = query(text(query, 2000, false));
        if (limit < 1 || limit > 20) throw invalid("检索数量须在 1 到 20 之间");
        if (courseId != null) requireCourse(current, courseId, false);
        if (terms.words().isEmpty()) return new SearchResult(List.of(), 0, false);
        List<Long> ids = courseId == null
                ? jdbc.queryForList("SELECT m.id FROM sc_learning_material m JOIN sc_learning_course c ON c.id=m.course_id WHERE c.owner_id=? ORDER BY m.id DESC LIMIT 201", Long.class, current.id())
                : jdbc.queryForList("SELECT id FROM sc_learning_material WHERE course_id=? ORDER BY id DESC LIMIT 201", Long.class, courseId);
        var candidates = new ArrayList<Ranked>();
        int skipped = 0; boolean truncated = ids.size() > MAX_SEARCH_DOCUMENTS;
        // Rank only this owner's bounded index before spending any source-read budget.
        for (long id : ids.stream().limit(MAX_SEARCH_DOCUMENTS).toList()) {
            try {
                Ranked candidate = rank(row(current, id), terms);
                if (candidate != null) candidates.add(candidate);
            } catch (ApplicationFault unavailable) { skipped++; }
        }
        candidates.sort(Comparator.comparingInt(Ranked::score).reversed()
                .thenComparing(Comparator.comparingLong((Ranked candidate) -> candidate.material().id()).reversed()));
        var hits = new ArrayList<Citation>();
        long readBytes = 0;
        for (Ranked candidate : candidates) {
            if (hits.size() >= limit) { truncated = true; break; }
            if (readBytes + MAX_DOCUMENT_BYTES > MAX_SEARCH_BYTES) { skipped++; truncated = true; continue; }
            // Failed reads retain the reservation: permission/I/O failures cannot bypass the byte budget.
            readBytes += MAX_DOCUMENT_BYTES;
            try {
                Material view = candidate.material();
                Snapshot fresh = snapshot(current, view.storageKey(), path(view.path()), false);
                readBytes -= MAX_DOCUMENT_BYTES - fresh.bytes();
                if (!fresh.hash().equals(candidate.hash())) { skipped++; continue; }
                // Re-check ownership at emission as another tab may delete the course/index during disk I/O.
                Row checked = row(current, view.id());
                if (!checked.hash().equals(candidate.hash()) || !checked.view().storageKey().equals(view.storageKey())
                        || !checked.view().path().equals(view.path())) { skipped++; continue; }
                hits.add(new Citation("L" + view.id() + "-" + candidate.segment(), view.name(), candidate.segment(),
                        candidate.excerpt(), view.id(), view.courseId(), view.storageKey(), view.path()));
            } catch (ApplicationFault unavailable) { skipped++; }
        }
        return new SearchResult(List.copyOf(hits), skipped, truncated);
    }

    /** Bounded lexical recall; common question words do not become evidence by themselves. */
    private static Query query(String value) {
        String normalized = foldCase(value);
        var words = new LinkedHashSet<String>();
        var matcher = QUERY_WORDS.matcher(normalized);
        while (matcher.find() && words.size() < MAX_QUERY_TERMS) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                for (int i = 0; i < points.length - 1 && words.size() < MAX_QUERY_TERMS; i++) {
                    String bigram = new String(points, i, 2);
                    if (!STOP_WORDS.contains(bigram) && bigram.indexOf('的') < 0 && bigram.indexOf('请') < 0
                            && bigram.indexOf('吗') < 0 && bigram.indexOf('呢') < 0) words.add(bigram);
                }
            } else if (!STOP_WORDS.contains(word)) words.add(word);
        }
        return new Query(normalized, List.copyOf(words));
    }

    private static Ranked rank(Row row, Query query) {
        if (row.text().isBlank()) return null;
        String body = foldCase(row.text());
        Passage passage = passage(body, query);
        FieldMatch name = field(foldCase(row.view().name()), query);
        FieldMatch tags = field(foldCase(row.view().tags()), query);
        FieldMatch title = field(foldCase(title(row.text())), query);
        long covered = passage.allTerms() | name.terms() | tags.terms() | title.terms();
        if (Long.bitCount(covered) < Math.min(2, query.words().size())) return null;
        int score = Long.bitCount(covered) * 20 + Long.bitCount(passage.allTerms()) * 5
                + passage.score() + name.score(18, 180) + tags.score(12, 120) + title.score(10, 100);
        return new Ranked(row.view(), row.hash(), score, passage.position() / PASSAGE_CHARS + 1,
                row.text().substring(passage.start(), passage.end()));
    }

    private static FieldMatch field(String text, Query query) {
        long terms = 0;
        for (int i = 0; i < query.words().size(); i++) {
            if (find(text, query.words().get(i), 0) >= 0) terms |= 1L << i;
        }
        return new FieldMatch(terms, find(text, query.phrase(), 0) >= 0);
    }

    /** Overlapping fixed windows bound memory and find dense passages even late in a long document. */
    private static Passage passage(String text, Query query) {
        int count = Math.max(1, (text.length() + PASSAGE_STEP - 1) / PASSAGE_STEP);
        long[] terms = new long[count];
        boolean[] phrases = new boolean[count];
        int[] positions = new int[count]; Arrays.fill(positions, -1);
        long allTerms = 0;
        for (int token = 0; token <= query.words().size(); token++) {
            boolean phrase = token == query.words().size();
            String word = phrase ? query.phrase() : query.words().get(token);
            for (int at = find(text, word, 0); at >= 0; at = find(text, word, at + 1)) {
                if (!phrase) allTerms |= 1L << token;
                int first = Math.max(0, (at + word.length() - PASSAGE_CHARS + PASSAGE_STEP - 1) / PASSAGE_STEP);
                int last = Math.min(count - 1, at / PASSAGE_STEP);
                for (int window = first; window <= last; window++) {
                    if (phrase) phrases[window] = true; else terms[window] |= 1L << token;
                    if (positions[window] < 0 || at < positions[window]) positions[window] = at;
                }
            }
        }
        int best = 0, bestScore = -1;
        for (int window = 0; window < count; window++) {
            int score = Long.bitCount(terms[window]) * 20 + (phrases[window] ? 120 : 0);
            if (score > bestScore) { best = window; bestScore = score; }
        }
        int start = best * PASSAGE_STEP;
        return new Passage(start, Math.min(text.length(), start + PASSAGE_CHARS), Math.max(0, positions[best]), allTerms, bestScore);
    }

    private static int find(String text, String word, int from) {
        for (int at = text.indexOf(word, from); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            if ((!asciiWord(word.charAt(0)) || at == 0 || !asciiWord(text.charAt(at - 1)))
                    && (!asciiWord(word.charAt(word.length() - 1)) || end == text.length() || !asciiWord(text.charAt(end)))) return at;
        }
        return -1;
    }

    private static boolean asciiWord(char character) { return character >= 'a' && character <= 'z' || character >= '0' && character <= '9'; }

    // ASCII case folding preserves the original character offsets used for citations.
    private static String foldCase(String value) {
        if (value == null) return "";
        char[] characters = value.toCharArray();
        for (int i = 0; i < characters.length; i++) if (characters[i] >= 'A' && characters[i] <= 'Z') characters[i] += 'a' - 'A';
        return new String(characters);
    }

    private static String title(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = text.indexOf('\n', start);
        return text.substring(start, Math.min(end < 0 ? text.length() : end, start + 200));
    }

    private Snapshot snapshot(Actor actor, String key, DrivePath path, boolean extract) {
        if (!path.name().toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|pdf|docx)$")) throw invalid("仅支持 TXT、MD、PDF 和 DOCX 文件");
        if (!parsing.tryAcquire()) throw new ApplicationFault("41000", 429, "索引任务繁忙，请稍后重试");
        try {
            Actor current = accounts.current(actor);
            if (mounts.listMounts(current).stream().noneMatch(m -> m.key().equals(key) && m.engine().equalsIgnoreCase("LOCAL"))) throw invalid("仅支持当前可访问的本地存储");
            try (var handle = mounts.readWithMetadata(current, key, path)) {
                if (handle.metadata().directory() || handle.metadata().size() > MAX_DOCUMENT_BYTES) throw invalid("资料大小不能超过 10 MiB");
                byte[] bytes = handle.input().readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (bytes.length > MAX_DOCUMENT_BYTES) throw invalid("资料大小不能超过 10 MiB");
                // A permission change during reading must fail closed, too.
                try (var checked = mounts.readWithMetadata(accounts.current(current), key, path)) {
                    if (checked.metadata().size() != bytes.length || !checked.metadata().modifiedAt().equals(handle.metadata().modifiedAt())) throw invalid("文件已变化，请重试");
                }
                return new Snapshot(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                        extract ? LearningTextExtractor.extract(path.name(), bytes) : null);
            }
        } catch (IOException | IllegalArgumentException failure) { throw invalid("无法安全读取或提取此资料"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { parsing.release(); }
    }

    private Row row(Actor actor, long id) {
        Actor current = accounts.current(actor);
        List<Row> rows = jdbc.query("SELECT m.* FROM sc_learning_material m JOIN sc_learning_course c ON c.id=m.course_id WHERE m.id=? AND c.owner_id=?",
                (r, index) -> new Row(material(r, index), r.getString("content_hash"), r.getString("indexed_text")), id, current.id());
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private Course requireCourse(Actor actor, long id, boolean lock) {
        Actor current = accounts.current(actor);
        List<Course> rows = jdbc.query("SELECT * FROM sc_learning_course WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""), this::course, id, current.id());
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private Course course(ResultSet r, int index) throws SQLException { return new Course(r.getLong("id"), r.getString("name"), r.getString("semester"), r.getLong("created_at"), r.getLong("updated_at")); }
    private Material material(ResultSet r, int index) throws SQLException {
        return new Material(r.getLong("id"), r.getLong("course_id"), r.getString("storage_key"), r.getString("path"), r.getString("name"), r.getString("tags"), 2,
                r.getInt("covered_chars"), r.getBoolean("truncated"), r.getObject("indexed_pages", Integer.class), r.getObject("total_pages", Integer.class),
                "本地文字检索；不含图片文字", java.time.Instant.ofEpochMilli(r.getLong("updated_at")).toString(), r.getLong("created_at"), r.getLong("updated_at"));
    }
    private void lockOwner(long id) {
        jdbc.update("INSERT INTO sc_learning_owner_guard(account_id) VALUES(?) ON DUPLICATE KEY UPDATE account_id=account_id", id);
        jdbc.queryForObject("SELECT account_id FROM sc_learning_owner_guard WHERE account_id=? FOR UPDATE", Long.class, id);
    }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private static DrivePath path(String value) {
        try { return DrivePath.of(text(value, 500, false)); } catch (IllegalArgumentException invalid) { throw invalid("文件路径无效"); }
    }
    private static String text(String value, int max, boolean optional) {
        String result = value == null ? "" : value.trim();
        if ((!optional && result.isBlank()) || result.length() > max || result.chars().anyMatch(Character::isISOControl)) throw invalid("输入为空、过长或包含无效字符");
        return result;
    }
    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private static ApplicationFault missing() { return new ApplicationFault("40400", 404, "个人课程或资料不存在"); }
    private record Row(Material view, String hash, String text) { }
    private record Snapshot(String hash, int bytes, LearningTextExtractor.Extracted extracted) { }
    private record Query(String phrase, List<String> words) { }
    private record FieldMatch(long terms, boolean phrase) {
        int score(int wordWeight, int phraseWeight) { return Long.bitCount(terms) * wordWeight + (phrase ? phraseWeight : 0); }
    }
    private record Passage(int start, int end, int position, long allTerms, int score) { }
    private record Ranked(Material material, String hash, int score, int segment, String excerpt) { }
}
