package im.tlgxy.cloud.module.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import im.tlgxy.cloud.module.learning.mapper.*;
import im.tlgxy.cloud.module.learning.model.*;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Private course collections and deterministic lexical retrieval, not embedding/model training. */
@Service
@RequiredArgsConstructor
public class LearningLibraryService {
    private final LearningCourseMapper courses;
    private final LearningMaterialMapper materials;
    private final LearningFileReader reader;
    private final AiAttachmentService extractor;
    private final UserService users;
    private static final int MAX_MATERIALS = 30;

    private int currentUser() {
        Integer id = TlgxyCloudAuthUtil.getCurrentUserId();
        var user = users.requireLoginUser(id);
        if (user == null || !Boolean.TRUE.equals(user.getEnable()) || id == 0
                || user.getRole() == null || user.getRole() == UserRoleEnum.GUEST) {
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

    @Transactional
    public synchronized LearningCourse saveCourse(Integer id, LearningRequests.Course request) {
        int owner = currentUser();
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
    }

    @Transactional
    public void deleteCourse(int id) {
        int owner = currentUser();
        requireCourse(id, owner);
        materials.delete(ownedMaterials(owner).eq(LearningMaterial::getCourseId, id));
        courses.delete(ownedCourses(owner).eq(LearningCourse::getId, id));
    }

    public List<LearningMaterial> materials(int courseId) {
        int owner = currentUser();
        requireCourse(courseId, owner);
        return materials.selectList(ownedMaterials(owner).eq(LearningMaterial::getCourseId, courseId)
                .select(LearningMaterial.class, info -> !Set.of("content", "fingerprint").contains(info.getProperty()))
                .orderByDesc(LearningMaterial::getId));
    }

    @Transactional
    public synchronized LearningMaterial add(LearningRequests.Material request) {
        int owner = currentUser();
        requireCourse(request.courseId(), owner);
        String path = LearningFileReader.normalizePath(request.path());
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
        index(material);
        materials.insert(material);
        return material;
    }

    @Transactional
    public LearningMaterial refresh(int id) {
        var material = requireMaterial(id, currentUser());
        index(material);
        materials.updateById(material);
        return material;
    }

    @Transactional
    public void deleteMaterial(int id) {
        int owner = currentUser();
        requireMaterial(id, owner);
        materials.delete(ownedMaterials(owner).eq(LearningMaterial::getId, id));
    }

    private void index(LearningMaterial material) {
        byte[] bytes = reader.read(material.getStorageKey(), material.getPath());
        material.setContent(extractor.extractLearningText(bytes, material.getName()));
        material.setFingerprint(fingerprint(bytes));
        material.setIndexedAt(new Date());
    }

    public SearchResult search(String query, Integer courseId) {
        int owner = currentUser();
        if (query == null || query.isBlank() || query.length() > 200) throw new BizException("检索词须为 1–200 个字符");
        if (courseId != null) requireCourse(courseId, owner);
        var filter = ownedMaterials(owner).eq(courseId != null, LearningMaterial::getCourseId, courseId)
                .orderByDesc(LearningMaterial::getId).last("LIMIT " + MAX_MATERIALS);
        List<MaterialCitation> matches = new ArrayList<>();
        Set<String> terms = terms(query);
        int skipped = 0;
        for (var material : materials.selectList(filter)) {
            // A fingerprint comparison detects even same-size, same-timestamp overwrites.
            try {
                if (!fingerprint(reader.read(material.getStorageKey(), material.getPath())).equals(material.getFingerprint())) {
                    skipped++;
                    continue;
                }
            } catch (BizException e) {
                skipped++;
                continue;
            }
            String content = material.getContent();
            for (int start = 0, segment = 1; start < content.length(); start += 600, segment++) {
                String excerpt = content.substring(start, Math.min(start + 700, content.length()));
                String searchable = (material.getName() + " " + material.getTags() + " " + excerpt).toLowerCase(Locale.ROOT);
                int score = (int) terms.stream().filter(searchable::contains).count();
                if (score == 0) continue;
                matches.add(new MaterialCitation("M" + material.getId() + "-S" + segment, material.getId(),
                        material.getCourseId(), material.getName(), material.getStorageKey(), material.getPath(), segment, excerpt, score));
            }
        }
        matches.sort(Comparator.comparingInt(MaterialCitation::score).reversed()
                .thenComparing(MaterialCitation::materialId).thenComparingInt(MaterialCitation::segment));
        return new SearchResult(matches.stream().limit(5).toList(), skipped,
                "仅检索本人添加的资料；变化、失效或不可访问的文件已跳过。无命中不代表资料中一定没有答案。");
    }

    static Set<String> terms(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.matches("[\\p{IsHan}]+") && token.length() > 1) {
                for (int i = 0; i < token.length() - 1; i++) tokens.add(token.substring(i, i + 2));
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
