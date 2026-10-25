package im.tlgxy.cloud.module.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.learning.application.LearningLexicalRetriever;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.domain.LearningDocumentPath;
import im.tlgxy.cloud.module.learning.mapper.*;
import im.tlgxy.cloud.module.learning.model.*;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Private course collections, lexical/optional bounded semantic retrieval; never model training. */
@Service
@RequiredArgsConstructor
public class LearningLibraryService {
    private final LearningCourseMapper courses;
    private final LearningMaterialMapper materials;
    private final LearningFileAccess reader;
    private final LearningContentExtractor extractor;
    private final UserService users;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningSemanticIndex semantic;
    private final ConcurrentHashMap<Integer, OwnerLock> writes = new ConcurrentHashMap<>();
    private final Semaphore parsingSlots = new Semaphore(4);
    // This bounds the entire search, including JDBC strings, vector decoding and live file checks.
    // The embedding client's separate HTTP limit does not bound those allocations.
    private final Semaphore searchSlots = new Semaphore(2);
    private static final int MAX_MATERIALS = 30;
    private static final int MAX_CITATIONS = 5;
    private static final Pattern QUERY_TERMS = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+");
    private static final Set<String> QUERY_FILLERS = Set.of("请问", "如何", "怎么", "怎样", "什么", "哪些", "帮我", "我想", "一下");

    private int currentUser() {
        Integer id = TlgxyCloudAuthUtil.getCurrentUserId();
        var user = users.requireLoginUser(id);
        if (user == null || !Boolean.TRUE.equals(user.getEnable()) || id == 0
                || user.getRole() == null || !user.getRole().isLoginRole()) {
            throw new BizException("请使用已启用的普通用户或管理员账号登录");
        }
        return id;
    }

    private LambdaQueryWrapper<LearningCourse> ownedCourses(int owner) {
        return new LambdaQueryWrapper<LearningCourse>().eq(LearningCourse::getOwnerUserId, owner);
    }

    private LambdaQueryWrapper<LearningMaterial> ownedMaterials(int owner) {
        return new LambdaQueryWrapper<LearningMaterial>().eq(LearningMaterial::getOwnerUserId, owner);
    }

    private LearningCourse requireCourse(int id, int owner) {
        var course = courses.selectOne(ownedCourses(owner).eq(LearningCourse::getId, id));
        if (course == null) throw new BizException("课程不存在或无权访问");
        return course;
    }

    private LearningMaterial requireMaterial(int id, int owner) {
        var material = materials.selectOne(ownedMaterials(owner).eq(LearningMaterial::getId, id));
        if (material == null) throw new BizException("资料不存在或无权访问");
        return material;
    }

    public List<LearningCourse> courses() {
        return courses.selectList(ownedCourses(currentUser()).orderByDesc(LearningCourse::getId));
    }

    public LearningCourse saveCourse(Integer id, LearningRequests.Course request) {
        int owner = currentUser();
        return write(owner, () -> {
        LearningCourse course;
        if (id == null) {
            if (courses.selectCount(ownedCourses(owner)) >= 50) throw new BizException("每个账号最多创建 50 门课程");
            course = new LearningCourse();
            course.setOwnerUserId(owner);
        } else {
            course = requireCourse(id, owner);
        }
        course.setName(request.name().trim());
        course.setSemester(request.semester().trim());
        if (id == null) courses.insert(course); else courses.updateById(course);
        return course;
        });
    }

    public void deleteCourse(int id) {
        int owner = currentUser();
        write(owner, () -> {
        requireCourse(id, owner);
        materials.delete(ownedMaterials(owner).eq(LearningMaterial::getCourseId, id));
        courses.delete(ownedCourses(owner).eq(LearningCourse::getId, id));
        return null;
        });
    }

    public List<LearningMaterial> materials(int courseId) {
        int owner = currentUser();
        requireCourse(courseId, owner);
        return materials.selectList(ownedMaterials(owner).eq(LearningMaterial::getCourseId, courseId)
                .select(LearningMaterial.class, info -> !Set.of("content", "fingerprint", "embeddingJson", "embeddingSignature").contains(info.getProperty()))
                .orderByDesc(LearningMaterial::getId));
    }

    public LearningMaterial add(LearningRequests.Material request) {
        int owner = currentUser();
        requireCourse(request.courseId(), owner);
        String path = LearningDocumentPath.normalize(request.path());
        if (materials.selectCount(ownedMaterials(owner)) >= MAX_MATERIALS) {
            throw new BizException("每个账号最多索引 30 份资料，请先移除不需要的索引");
        }
        if (materials.selectCount(ownedMaterials(owner).eq(LearningMaterial::getCourseId, request.courseId())
                .eq(LearningMaterial::getStorageKey, request.storageKey()).eq(LearningMaterial::getPath, path)) > 0) {
            throw new BizException("该文件已加入课程，可使用重新索引更新内容");
        }
        var material = new LearningMaterial();
        material.setOwnerUserId(owner);
        material.setCourseId(request.courseId());
        material.setStorageKey(request.storageKey());
        material.setPath(path);
        material.setName(path.substring(path.lastIndexOf('/') + 1));
        material.setTags(normalizeTags(request.tags()));
        // Disk reading and PDF/DOCX parsing do not hold a database transaction or a global lock.
        index(material);
        return withOwnerLock(owner, () -> {
            verifyPreparedFile(material);
            return transactions.execute(status -> {
                currentUser();
                requireCourse(request.courseId(), owner);
                if (materials.selectCount(ownedMaterials(owner)) >= MAX_MATERIALS)
                    throw new BizException("每个账号最多索引 30 份资料，请先移除不需要的索引");
                if (materials.selectCount(ownedMaterials(owner).eq(LearningMaterial::getCourseId, request.courseId())
                        .eq(LearningMaterial::getStorageKey, request.storageKey()).eq(LearningMaterial::getPath, path)) > 0)
                    throw new BizException("该文件已加入课程，可使用重新索引更新内容");
                materials.insert(material);
                return material;
            });
        });
    }

    public LearningMaterial refresh(int id) {
        int owner = currentUser();
        var material = requireMaterial(id, owner);
        String previousFingerprint = material.getFingerprint();
        Date previousIndexedAt = material.getIndexedAt();
        index(material);
        return withOwnerLock(owner, () -> {
            verifyPreparedFile(material);
            return transactions.execute(status -> {
                currentUser();
                requireCourse(material.getCourseId(), owner);
                var current = requireMaterial(id, owner);
                if (!Objects.equals(current.getFingerprint(), previousFingerprint)
                        || !Objects.equals(current.getIndexedAt(), previousIndexedAt)
                        || !Objects.equals(current.getStorageKey(), material.getStorageKey())
                        || !Objects.equals(current.getPath(), material.getPath()))
                    throw new BizException("资料索引已被其他操作更新，请刷新后重试");
                materials.updateById(material);
                return material;
            });
        });
    }

    /** User-selected repair: validate and index the new file before changing an existing private record. */
    public LearningMaterial relink(int id, LearningRequests.Relink request) {
        int owner = currentUser();
        var previous = requireMaterial(id, owner);
        String previousPath = previous.getPath();
        String previousKey = previous.getStorageKey();
        String previousFingerprint = previous.getFingerprint();
        Date previousIndexedAt = previous.getIndexedAt();
        String path = LearningDocumentPath.normalize(request.path());
        previous.setStorageKey(request.storageKey());
        previous.setPath(path);
        previous.setName(path.substring(path.lastIndexOf('/') + 1));
        index(previous); // External I/O and parsing must not hold a database transaction.
        return withOwnerLock(owner, () -> {
            verifyPreparedFile(previous);
            return transactions.execute(status -> {
                currentUser();
                requireCourse(previous.getCourseId(), owner);
                var current = requireMaterial(id, owner);
                if (!Objects.equals(current.getPath(), previousPath) || !Objects.equals(current.getStorageKey(), previousKey)
                        || !Objects.equals(current.getFingerprint(), previousFingerprint)
                        || !Objects.equals(current.getIndexedAt(), previousIndexedAt))
                    throw new BizException("资料已被其他操作更新，请刷新后重试");
                if (materials.selectCount(ownedMaterials(owner).eq(LearningMaterial::getCourseId, current.getCourseId())
                        .eq(LearningMaterial::getStorageKey, request.storageKey()).eq(LearningMaterial::getPath, path)
                        .ne(LearningMaterial::getId, id)) > 0)
                    throw new BizException("此文件已在该课程建立索引，请勿重复关联");
                materials.updateById(previous);
                return previous;
            });
        });
    }

    public record PathChange(Integer id, Integer courseId, String oldPath, String newPath,
                             String fingerprint, Date indexedAt) { }
    public record PathChangePlan(int owner, String storageKey, List<PathChange> changes) { }

    /** Capture only this actor's private references before a file write. No content or other users' indexes. */
    public PathChangePlan preparePathChange(String storageKey, String oldPath, String newPath, boolean folder) {
        int owner = currentUser();
        String source = LearningDocumentPath.normalize(oldPath);
        String target = LearningDocumentPath.normalize(newPath);
        List<LearningMaterial> indexed = materials.selectList(ownedMaterials(owner)
                .eq(LearningMaterial::getStorageKey, storageKey)
                .select(LearningMaterial::getId, LearningMaterial::getCourseId, LearningMaterial::getPath,
                        LearningMaterial::getFingerprint, LearningMaterial::getIndexedAt));
        List<PathChange> changes = new ArrayList<>();
        for (var material : indexed) {
            if (material.getPath() == null || !(folder ? material.getPath().startsWith(source + "/") : material.getPath().equals(source))) continue;
            String next = target + material.getPath().substring(source.length());
            // A collision must be reported before the file move, not silently overwrite another course reference.
            if (indexed.stream().anyMatch(other -> !Objects.equals(other.getId(), material.getId())
                    && Objects.equals(other.getCourseId(), material.getCourseId()) && Objects.equals(other.getPath(), next)))
                throw new BizException("目标路径已存在资料索引，请先处理重复关联");
            changes.add(new PathChange(material.getId(), material.getCourseId(), material.getPath(), next,
                    material.getFingerprint(), material.getIndexedAt() == null ? null : new Date(material.getIndexedAt().getTime())));
        }
        return new PathChangePlan(owner, storageKey, List.copyOf(changes));
    }

    /** Call only after confirmed file success. Any failure has an explicit no-replay result and repair entry. */
    public void completePathChange(PathChangePlan plan) {
        if (plan == null || plan.changes().isEmpty()) return;
        try {
            if (currentUser() != plan.owner()) throw new BizException("账号上下文已变化");
            withOwnerLock(plan.owner(), () -> {
                Map<String, String> fingerprints = new HashMap<>();
                for (var change : plan.changes()) {
                    String actual = fingerprints.computeIfAbsent(change.newPath(), path -> reader.fingerprint(plan.storageKey(), path));
                    if (!Objects.equals(actual, change.fingerprint())) throw new BizException("文件内容或授权已变化");
                }
                return transactions.execute(status -> {
                    currentUser();
                    for (var change : plan.changes()) {
                        var current = materials.selectOne(ownedMaterials(plan.owner()).eq(LearningMaterial::getId, change.id()));
                        if (current == null) continue; // Concurrent removal must not resurrect an index.
                        if (!Objects.equals(current.getPath(), change.oldPath()) || !Objects.equals(current.getStorageKey(), plan.storageKey())
                                || !Objects.equals(current.getFingerprint(), change.fingerprint())
                                || !Objects.equals(current.getIndexedAt(), change.indexedAt())) throw new BizException("索引已被其他操作更新");
                        if (materials.selectCount(ownedMaterials(plan.owner()).eq(LearningMaterial::getCourseId, current.getCourseId())
                                .eq(LearningMaterial::getStorageKey, plan.storageKey()).eq(LearningMaterial::getPath, change.newPath())
                                .ne(LearningMaterial::getId, change.id())) > 0) throw new BizException("目标索引关联冲突");
                        var update = new LambdaUpdateWrapper<LearningMaterial>().eq(LearningMaterial::getId, change.id())
                                .eq(LearningMaterial::getOwnerUserId, plan.owner()).eq(LearningMaterial::getStorageKey, plan.storageKey())
                                .eq(LearningMaterial::getPath, change.oldPath())
                                .eq(change.fingerprint() != null, LearningMaterial::getFingerprint, change.fingerprint())
                                .isNull(change.fingerprint() == null, LearningMaterial::getFingerprint)
                                .eq(change.indexedAt() != null, LearningMaterial::getIndexedAt, change.indexedAt())
                                .isNull(change.indexedAt() == null, LearningMaterial::getIndexedAt)
                                .set(LearningMaterial::getPath, change.newPath())
                                .set(LearningMaterial::getName, change.newPath().substring(change.newPath().lastIndexOf('/') + 1));
                        if (materials.update(null, update) != 1) throw new BizException("资料索引写入发生冲突");
                    }
                    return null;
                });
            });
        } catch (RuntimeException failure) {
            throw new LearningPathSyncException(); // Never claim the already completed file write failed safely.
        }
    }

    public void deleteMaterial(int id) {
        int owner = currentUser();
        write(owner, () -> {
        requireMaterial(id, owner);
        materials.delete(ownedMaterials(owner).eq(LearningMaterial::getId, id));
        return null;
        });
    }

    private void index(LearningMaterial material) {
        if (!parsingSlots.tryAcquire()) throw new BizException("资料解析繁忙，请稍后重试");
        try {
        byte[] bytes = reader.read(material.getStorageKey(), material.getPath());
        var extraction = extractor.extract(bytes, material.getName());
        material.setContent(extraction.content());
        material.setIndexVersion(2);
        material.setCoveredChars(extraction.coveredChars());
        material.setIndexedPages(extraction.indexedPages());
        material.setTotalPages(extraction.totalPages());
        material.setTruncated(extraction.truncated());
        material.setIndexNotice(extraction.indexNotice());
        material.setFingerprint(fingerprint(bytes));
        material.setIndexedAt(new Date());
        if (semantic != null) {
            if (semantic.configured()) verifyPreparedFile(material); // Recheck before sending private excerpts externally.
            semantic.prepare(material);
        }
        } finally { parsingSlots.release(); }
    }

    private void verifyPreparedFile(LearningMaterial material) {
        currentUser();
        // Re-resolves the user's current root, grants, ancestor filters/passwords and whole-file hash.
        if (!material.getFingerprint().equals(reader.fingerprint(material.getStorageKey(), material.getPath())))
            throw new BizException("解析期间源文件已变化，请重新索引");
    }

    private <T> T write(int owner, Supplier<T> work) {
        return withOwnerLock(owner, () -> transactions.execute(status -> {
            currentUser();
            return work.get();
        }));
    }

    /** The owner lock spans the actual commit, not merely a transactional method's return. */
    private <T> T withOwnerLock(int owner, Supplier<T> work) {
        OwnerLock entry = writes.compute(owner, (key, existing) -> {
            OwnerLock selected = existing == null ? new OwnerLock() : existing;
            selected.references++;
            return selected;
        });
        entry.lock.lock();
        try { return work.get(); }
        finally {
            entry.lock.unlock();
            writes.compute(owner, (key, existing) -> --entry.references == 0 ? null : entry);
        }
    }

    private static final class OwnerLock {
        final ReentrantLock lock = new ReentrantLock();
        int references; // Only accessed inside ConcurrentHashMap.compute for this owner.
    }

    public SearchResult search(String query, Integer courseId) {
        if (query == null || query.isBlank() || query.length() > 200) throw new BizException("检索词须为 1–200 个字符");
        if (!searchSlots.tryAcquire()) throw new BizException("资料检索繁忙，请稍后重试");
        try {
            int owner = currentUser();
            if (courseId != null) requireCourse(courseId, owner);
            // Never load historical oversized vectors along with the bounded lexical content.
            var filter = ownedMaterials(owner).eq(courseId != null, LearningMaterial::getCourseId, courseId)
                    .select(LearningMaterial.class, info -> !Set.of("embeddingJson", "embeddingSignature").contains(info.getProperty()))
                    .orderByDesc(LearningMaterial::getId).last("LIMIT " + MAX_MATERIALS);
            List<LearningMaterial> indexed = materials.selectList(filter);
            if (semantic != null && semantic.configured() && !indexed.isEmpty())
                loadBoundedSemanticIndexes(owner, indexed);
            return searchCandidates(indexed, terms(query), query);
        } finally { searchSlots.release(); }
    }

    /** SQL rejects old large blobs before JDBC; the owner-scoped second read is at most 30 MiB. */
    private void loadBoundedSemanticIndexes(int owner, List<LearningMaterial> indexed) {
        var filter = ownedMaterials(owner)
                .in(LearningMaterial::getId, indexed.stream().map(LearningMaterial::getId).toList())
                .apply("LENGTH(embedding_json) <= {0}", LearningSemanticIndex.MAX_JSON_CHARS)
                .select(LearningMaterial::getId, LearningMaterial::getEmbeddingJson,
                        LearningMaterial::getEmbeddingSignature, LearningMaterial::getSemanticSegments)
                .last("LIMIT " + MAX_MATERIALS);
        // Refresh/relink/move between the two reads must not pair new vectors with old text.
        // Matching is done in SQL so the result still contains only the four vector fields.
        filter.and(snapshots -> {
            boolean first = true;
            for (var material : indexed) {
                if (!first) snapshots.or();
                first = false;
                snapshots.nested(version -> version.eq(LearningMaterial::getId, material.getId())
                        .eq(LearningMaterial::getCourseId, material.getCourseId())
                        .eq(LearningMaterial::getStorageKey, material.getStorageKey())
                        .eq(LearningMaterial::getPath, material.getPath())
                        .eq(material.getFingerprint() != null, LearningMaterial::getFingerprint, material.getFingerprint())
                        .isNull(material.getFingerprint() == null, LearningMaterial::getFingerprint)
                        .eq(material.getIndexedAt() != null, LearningMaterial::getIndexedAt, material.getIndexedAt())
                        .isNull(material.getIndexedAt() == null, LearningMaterial::getIndexedAt));
            }
        });
        Map<Integer, LearningMaterial> byId = new HashMap<>();
        for (var material : indexed) byId.put(material.getId(), material);
        for (var vector : materials.selectList(filter)) {
            var material = byId.get(vector.getId());
            if (material == null) continue;
            material.setEmbeddingJson(vector.getEmbeddingJson());
            material.setEmbeddingSignature(vector.getEmbeddingSignature());
            material.setSemanticSegments(vector.getSemanticSegments());
        }
    }

    /** One verified opening excerpt per material for a bounded course overview. */
    public SearchResult courseOverview(int courseId) {
        int owner = currentUser();
        requireCourse(courseId, owner);
        List<LearningMaterial> indexed = materials.selectList(ownedMaterials(owner)
                .eq(LearningMaterial::getCourseId, courseId).orderByDesc(LearningMaterial::getId)
                .select(LearningMaterial.class, info -> !Set.of("embeddingJson", "embeddingSignature").contains(info.getProperty()))
                .last("LIMIT " + MAX_MATERIALS));
        List<MaterialCitation> verified = new ArrayList<>();
        int skipped = 0;
        for (LearningMaterial material : indexed) {
            if (verified.size() >= MAX_CITATIONS) break;
            if (material.getContent() == null || material.getContent().isBlank()) continue;
            try {
                if (!reader.fingerprint(material.getStorageKey(), material.getPath()).equals(material.getFingerprint())) {
                    skipped++;
                    continue;
                }
            } catch (BizException e) {
                skipped++;
                continue;
            }
            String excerpt = material.getContent().substring(0, Math.min(700, material.getContent().length()));
            verified.add(new MaterialCitation("M" + material.getId() + "-S1", material.getId(), courseId,
                    material.getName(), material.getStorageKey(), material.getPath(), 1, excerpt, 1));
        }
        return new SearchResult(List.copyOf(verified), skipped,
                "仅展示本人课程最多 5 份资料的开头片段，均已复核当前文件权限与内容；不能代表全部课程资料。");
    }

    /** Rank private index text first; no excerpt is returned before live access/content validation. */
    SearchResult searchCandidates(List<LearningMaterial> indexed, Set<String> terms) {
        return searchCandidates(indexed, terms, null);
    }

    SearchResult searchCandidates(List<LearningMaterial> indexed, Set<String> terms, String query) {
        var lexical = LearningLexicalRetriever.rankWithOrigins(indexed, terms, query);
        List<MaterialCitation> matches = lexical.matches();
        Set<String> metadataOnly = new HashSet<>(lexical.metadataOnlyReferences());
        Map<Integer, LearningMaterial> byId = new HashMap<>();
        for (var material : indexed) {
            byId.put(material.getId(), material);
        }
        String retrievalNotice = "当前使用关键词检索。";
        if (semantic != null && query != null) {
            var ranking = semantic.rank(indexed, query, matches);
            matches = ranking.matches();
            retrievalNotice = ranking.notice();
            metadataOnly.removeAll(ranking.semanticReferences());
        }
        // Only memoize within this request. Never reuse permissions or file hashes across requests.
        Map<FileLocation, Optional<String>> fingerprints = new HashMap<>();
        Set<Integer> skipped = new HashSet<>();
        List<MaterialCitation> verified = new ArrayList<>();
        for (var candidate : LearningLexicalRetriever.diversify(matches, metadataOnly)) {
            var material = byId.get(candidate.materialId());
            var location = new FileLocation(material.getStorageKey(), material.getPath());
            var actual = fingerprints.computeIfAbsent(location, key -> {
                try {
                    return Optional.of(reader.fingerprint(key.storageKey(), key.path()));
                } catch (BizException e) {
                    return Optional.empty();
                }
            });
            if (actual.isEmpty() || !actual.get().equals(material.getFingerprint())) {
                skipped.add(material.getId());
                continue;
            }
            verified.add(candidate);
            if (verified.size() == MAX_CITATIONS) break;
        }
        return new SearchResult(List.copyOf(verified), skipped.size(),
                retrievalNotice + "优先展示不同文件的相关片段，空位再补充同一文件；文件名或标签命中不等于正文有答案，分数仅用于排序，不是答案准确率。仅检索本人添加的资料；返回片段均已校验当前权限和文件内容。跳过数仅统计本次校验失效的候选资料，非全库失效数。无命中不代表资料中一定没有答案。");
    }

    private record FileLocation(String storageKey, String path) { }

    static Set<String> terms(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = QUERY_TERMS.matcher(java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.matches("[\\p{IsHan}]+") && token.codePointCount(0, token.length()) > 1) {
                int[] points = token.codePoints().toArray();
                for (int i = 0; i < points.length - 1; i++) {
                    String pair = new String(points, i, 2);
                    if (!QUERY_FILLERS.contains(pair)) tokens.add(pair);
                }
            } else tokens.add(token);
        }
        return tokens;
    }

    private static String normalizeTags(String tags) {
        return String.join(",", Arrays.stream(tags.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
    }

    static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record SearchResult(List<MaterialCitation> citations, int skipped, String notice) { }

    /** Synchronous listener participates in the user-deletion transaction. */
    @org.springframework.context.event.EventListener
    public void onUserDeleted(im.tlgxy.cloud.module.user.event.UserDeleteEvent event) {
        materials.delete(ownedMaterials(event.getId()));
        courses.delete(ownedCourses(event.getId()));
    }
}
