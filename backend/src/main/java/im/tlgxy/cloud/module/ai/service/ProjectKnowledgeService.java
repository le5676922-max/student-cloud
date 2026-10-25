package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.config.AiRagProperties;
import im.tlgxy.cloud.module.ai.feedback.AiKnowledgePublicationService;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeDocument;
import im.tlgxy.cloud.module.ai.model.result.AiKnowledgeResult;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Retrieves local project knowledge visible to the current account type. */
@Slf4j
@Service
public class ProjectKnowledgeService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._:/-]*|[\\p{IsHan}]+");

    private static final Set<String> STOP_TERMS = Set.of(
            "的", "了", "和", "是", "我", "你", "他", "在", "有", "为", "可", "要", "吗", "呢", "请", "与",
            "请问", "如何", "怎么", "么办", "怎样", "什么", "哪些", "多少", "是多", "帮我", "我想", "一下"
    );

    private static final List<Set<String>> SITE_SYNONYM_GROUPS = List.of(
            Set.of("上传", "上传文件", "传文件", "存入", "存进", "导入"),
            Set.of("下载", "下载文件", "取回", "保存到本地"),
            Set.of("存储源", "云盘", "网盘", "存储空间", "存储节点"),
            Set.of("分享", "共享", "分享链接", "外链"),
            Set.of("直链", "直接链接", "原始链接"),
            Set.of("短链", "短网址", "短链接"),
            Set.of("密码文件夹", "加密文件夹", "密码保护", "文件加密"),
            Set.of("删除", "移除", "清除"),
            Set.of("登录", "登陆", "登入"),
            Set.of("管理员", "后台", "管理端"),
            Set.of("小芽", "助手", "agent"),
            Set.of("知识库", "rag", "检索增强"),
            Set.of("课程资料", "学习资料", "课程库"),
            Set.of("整理", "归类", "分类"),
            Set.of("批量归档", "批量索引", "归档资料"),
            Set.of("重新索引", "刷新索引", "更新索引"),
            Set.of("响应慢", "延迟", "卡顿")
    );

    private final AiRagProperties properties;
    private final Map<KnowledgeAudience, List<IndexedDocument>> documents;
    private AiKnowledgePublicationService dynamicKnowledge;

    /** Optional to preserve standalone/offline knowledge consumers and existing constructor compatibility. */
    @Autowired(required = false)
    public void setDynamicKnowledge(AiKnowledgePublicationService dynamicKnowledge) {
        this.dynamicKnowledge = dynamicKnowledge;
    }

    public ProjectKnowledgeService(AiRagProperties properties,
                                   ObjectMapper objectMapper,
                                   ResourceLoader resourceLoader) {
        this.properties = properties;
        this.documents = Map.of(
                KnowledgeAudience.ADMIN,
                loadDocuments(objectMapper, resourceLoader, properties.getAdminLocation(), KnowledgeAudience.ADMIN),
                KnowledgeAudience.USER,
                loadDocuments(objectMapper, resourceLoader, properties.getUserLocation(), KnowledgeAudience.USER)
        );
        log.info("AI RAG knowledge loaded: admin={}, user={}",
                documents.get(KnowledgeAudience.ADMIN).size(),
                documents.get(KnowledgeAudience.USER).size());
    }

    /** Maps the built-in administrator to admin knowledge and every other account to user knowledge. */
    public KnowledgeAudience resolveCurrentAudience() {
        User currentUser = TlgxyCloudAuthUtil.getCurrentUser();
        if (currentUser == null || !Boolean.TRUE.equals(currentUser.getEnable())
                || currentUser.getRole() == null || !currentUser.getRole().isLoginRole()) {
            throw new BizException("当前账号已被禁用，无法使用 AI 助手");
        }
        return currentUser.getRole() == UserRoleEnum.ADMIN
                ? KnowledgeAudience.ADMIN
                : KnowledgeAudience.USER;
    }

    /** Returns the most relevant entries from only the requested audience's knowledge base. */
    public List<AiKnowledgeResult> search(String question, KnowledgeAudience audience) {
        if (!properties.isEnabled() || StringUtils.isBlank(question)) {
            return List.of();
        }

        Set<String> queryTerms = expandSiteSynonyms(tokenize(question));
        if (queryTerms.isEmpty()) return List.of();
        String compactQuestion = compact(question);

        List<IndexedDocument> candidates = new ArrayList<>(documents.getOrDefault(audience, List.of()));
        if (dynamicKnowledge != null) {
            candidates.addAll(dynamicKnowledge.visibleDocuments(audience).stream().map(this::index).toList());
        }
        return candidates.stream()
                .map(document -> toScoredDocument(document, queryTerms, compactQuestion))
                .filter(result -> result.score() > 0 && result.score() >= properties.getMinScore())
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed()
                        .thenComparing(result -> result.document().document().getId()))
                .limit(properties.getTopK())
                .map(result -> toResult(result, audience))
                .toList();
    }

    /** Builds model context without allowing knowledge to cross account boundaries. */
    public String buildContext(String question, KnowledgeAudience audience) {
        return buildContext(search(question, audience), audience);
    }

    public String buildContext(List<AiKnowledgeResult> results, KnowledgeAudience audience) {
        results = results.stream().filter(result -> result != null && audience.getKey().equals(result.getAudience())).toList();
        if (results.isEmpty()) {
            return audience.getDescription() + "知识库中没有检索到与本次问题直接相关的内容。";
        }

        StringBuilder context = new StringBuilder("以下内容来自学生云盘的")
                .append(audience.getDescription())
                .append("知识库，请优先依据这些资料回答：");
        for (AiKnowledgeResult result : results) {
            String section = "\n\n[K:" + result.getId() + "] " + result.getTitle() + "\n来源：" + result.getSource() + "\n" + result.getContent();
            int remaining = properties.getMaxContextChars() - context.length();
            if (remaining <= 0) {
                break;
            }
            context.append(section, 0, Math.min(section.length(), remaining));
        }
        return context.toString();
    }

    private List<IndexedDocument> loadDocuments(ObjectMapper objectMapper,
                                                ResourceLoader resourceLoader,
                                                String location,
                                                KnowledgeAudience audience) {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            List<KnowledgeDocument> loaded = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<KnowledgeDocument>>() { }
            );
            validateDocuments(loaded, audience);
            return loaded.stream().map(this::index).toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 AI RAG 知识库：" + location, e);
        }
    }

    private void validateDocuments(List<KnowledgeDocument> loaded, KnowledgeAudience audience) {
        if (loaded == null || loaded.isEmpty()) {
            throw new IllegalStateException(audience.getDescription() + "知识库不能为空");
        }

        Set<String> ids = new HashSet<>();
        for (KnowledgeDocument document : loaded) {
            if (StringUtils.isAnyBlank(document.getId(), document.getTitle(), document.getContent(), document.getSource())
                    || document.getTags() == null || document.getTags().isEmpty()) {
                throw new IllegalStateException("AI RAG 知识条目的 id、title、content、source、tags 不能为空");
            }
            if (!audience.getKey().equals(document.getAudience())) {
                throw new IllegalStateException("知识条目受众错误：" + document.getId());
            }
            if (!ids.add(document.getId())) {
                throw new IllegalStateException("AI RAG 知识条目 id 重复：" + document.getId());
            }
        }
    }

    private IndexedDocument index(KnowledgeDocument document) {
        List<String> tags = document.getTags() == null ? List.of() : document.getTags();
        Set<String> tagTerms = new LinkedHashSet<>();
        for (String tag : tags) {
            tagTerms.addAll(tokenize(tag));
        }
        return new IndexedDocument(
                document,
                tokenize(document.getTitle() + " " + StringUtils.defaultString(document.getQuestion())),
                tagTerms,
                tokenize(document.getContent()),
                compact(document.getTitle()),
                tags.stream().map(this::compact).toList()
        );
    }

    private ScoredDocument toScoredDocument(IndexedDocument document,
                                             Set<String> queryTerms,
                                             String compactQuestion) {
        double score = 0;
        for (String term : queryTerms) {
            int termLength = term.codePointCount(0, term.length());
            double termWeight = termLength >= 2 ? 1.0 : 0.25;
            if (document.titleTerms().contains(term)) {
                score += 6 * termWeight;
            }
            if (document.tagTerms().contains(term)) {
                score += 5 * termWeight;
            }
            if (document.contentTerms().contains(term)) {
                score += termWeight;
            }
        }

        if (StringUtils.isNotBlank(compactQuestion)
                && (compactQuestion.contains(document.compactTitle())
                || document.compactTitle().contains(compactQuestion))) {
            score += 10;
        }
        for (String tag : document.compactTags()) {
            if (tag.length() >= 2 && compactQuestion.contains(tag)) {
                score += 8;
            }
        }
        return new ScoredDocument(document, score);
    }

    private AiKnowledgeResult toResult(ScoredDocument result, KnowledgeAudience audience) {
        KnowledgeDocument document = result.document().document();
        double roundedScore = Math.round(result.score() * 100) / 100.0;
        return new AiKnowledgeResult(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                document.getSource(),
                audience.getKey(),
                roundedScore
        );
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalize(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (isChinese(token)) {
                addChineseTerms(token, terms);
            } else if (token.length() > 1) {
                terms.add(token);
            }
        }
        return terms;
    }

    private Set<String> expandSiteSynonyms(Set<String> terms) {
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (Set<String> group : SITE_SYNONYM_GROUPS) {
            boolean matched = group.stream().map(this::tokenize).anyMatch(terms::containsAll);
            if (matched) {
                group.forEach(term -> expanded.addAll(tokenize(term)));
            }
        }
        return expanded;
    }

    private void addChineseTerms(String text, Set<String> terms) {
        List<String> characters = new ArrayList<>();
        text.codePoints().forEach(codePoint -> characters.add(new String(Character.toChars(codePoint))));
        // Avoid accumulating relevance from unrelated single Chinese characters.
        if (characters.size() == 1 && !STOP_TERMS.contains(text)) terms.add(text);
        for (int i = 0; i < characters.size() - 1; i++) {
            String term = characters.get(i) + characters.get(i + 1);
            if (!STOP_TERMS.contains(term)) terms.add(term);
        }
    }

    private boolean isChinese(String token) {
        return token.codePoints()
                .allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String compact(String text) {
        return normalize(text)
                .replaceAll("[^a-z0-9\\p{IsHan}]", "");
    }

    private String normalize(String text) {
        return java.text.Normalizer.normalize(StringUtils.defaultString(text), java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private record IndexedDocument(KnowledgeDocument document,
                                   Set<String> titleTerms,
                                   Set<String> tagTerms,
                                   Set<String> contentTerms,
                                   String compactTitle,
                                   List<String> compactTags) {
    }

    private record ScoredDocument(IndexedDocument document, double score) {
    }
}
