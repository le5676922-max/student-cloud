package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.mapper.AiLongTermMemoryMapper;
import im.tlgxy.cloud.module.ai.model.memory.AiMemoryProfile;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;
import java.util.regex.Pattern;

/** Explicitly saved user preferences, never a transcript, permission grant or tool result cache. */
@Service
@RequiredArgsConstructor
public class AiLongTermMemoryService {
    public static final int MAX_ENTRIES = 50;
    public static final int MAX_CHARS = 500;
    public static final int MAX_RECALL_CHARS = 2500;
    // Includes JSON escaping/metadata for all 50 x 500-character entries.
    private static final int MAX_JSON_CHARS = 64000;
    private static final Pattern PRIVATE_DATA = Pattern.compile(
            "密码|口令|授权码|验证码|认证码|密钥|私钥|令牌|password|passwd|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|client[ _-]?secret|authorization|\\btoken\\b|(?:token|secret|cookie)\\s*(?:[:=：]|是|为)|bearer\\s+|-----BEGIN|\\bsk-[a-z0-9_-]{8,}|\\b[a-z0-9_+/=-]{32,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_OR_LINK = Pattern.compile(
            "https?://|file:/{0,2}|[a-z]:[\\\\/]|\\\\\\\\[^\\s]+|(?:^|\\s)/(?:etc|home|root|usr|var|opt|app|data|mnt|tmp)(?:/|\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVILEGE_COMMAND = Pattern.compile(
            "(?:忽略|跳过|绕过).{0,12}(?:系统规则|权限|鉴权|确认|验证)|(?:无需|不需要).{0,6}(?:权限检查|用户确认)|忽略所有(?:指令|规则)");
    private static final Pattern QUERY_WORDS = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{1,}");
    private static final TypeReference<List<Item>> ITEMS = new TypeReference<>() { };

    private final AiLongTermMemoryMapper memories;
    private final UserService users;
    private final ObjectMapper json;

    public record Item(String id, String content, String createdAt) { }
    public record State(boolean enabled, int maxEntries, int maxChars, List<Item> items) { }

    public State state(int owner) {
        requireOwner(owner);
        AiMemoryProfile profile = memories.find(owner);
        return profile == null ? view(true, List.of()) : view(enabled(profile), decode(profile));
    }

    public State remember(int owner, String content) {
        requireOwner(owner);
        String normalized = normalize(content);
        AiMemoryProfile profile = writableProfile(owner);
        if (!enabled(profile)) throw new BizException("长期记忆已关闭，请先在记忆面板启用");
        List<Item> entries = new ArrayList<>(decode(profile));
        if (entries.stream().anyMatch(item -> item.content().equals(normalized))) return view(true, entries);
        if (entries.size() >= MAX_ENTRIES) throw new BizException("最多保存 50 条长期记忆，请先删除不再需要的内容");
        entries.add(new Item(UUID.randomUUID().toString(), normalized, Instant.now().toString()));
        persist(owner, profile, true, entries);
        return view(true, entries);
    }

    public State setEnabled(int owner, boolean enabled) {
        requireOwner(owner);
        AiMemoryProfile profile = writableProfile(owner);
        List<Item> entries = decode(profile);
        persist(owner, profile, enabled, entries);
        return view(enabled, entries);
    }

    /** Edit one owned note without silently merging, reordering or recreating its identity. */
    public State update(int owner, String id, String content) {
        return update(owner, id, content, null);
    }

    /** The optional original text also protects edits opened before another window saved a change. */
    public State update(int owner, String id, String content, String expectedContent) {
        requireOwner(owner);
        validateId(id);
        String normalized = normalize(content);
        AiMemoryProfile profile = memories.find(owner);
        if (profile == null) throw new BizException("该记忆不存在或已删除");
        if (!enabled(profile)) throw new BizException("长期记忆已关闭，请先在记忆面板启用");
        List<Item> entries = new ArrayList<>(decode(profile));
        for (int index = 0; index < entries.size(); index++) {
            Item previous = entries.get(index);
            if (!previous.id().equals(id)) continue;
            if (previous.content().equals(normalized)) return view(true, entries);
            if (expectedContent != null && !expectedContent.equals(previous.content())) {
                throw new BizException("这条记忆已在其他窗口修改，请刷新后重试");
            }
            if (entries.stream().anyMatch(item -> !item.id().equals(id) && item.content().equals(normalized))) {
                throw new BizException("已有相同内容的记忆，请修改内容后再保存");
            }
            entries.set(index, new Item(previous.id(), normalized, previous.createdAt()));
            persist(owner, profile, true, entries);
            return view(true, entries);
        }
        // Never disclose whether the supplied ID belongs to another account.
        throw new BizException("该记忆不存在或已删除");
    }

    public State forget(int owner, String id) {
        requireOwner(owner);
        validateId(id);
        AiMemoryProfile profile = writableProfile(owner);
        List<Item> entries = new ArrayList<>(decode(profile));
        // Never disclose whether the ID exists in another account.
        if (!entries.removeIf(item -> item.id().equals(id))) throw new BizException("该记忆不存在或已删除");
        persist(owner, profile, enabled(profile), entries);
        return view(enabled(profile), entries);
    }

    public State clear(int owner) {
        requireOwner(owner);
        AiMemoryProfile profile = writableProfile(owner);
        // Keep the row/version barrier: an earlier writer must not recreate a cleared snapshot.
        persist(owner, profile, enabled(profile), List.of());
        return view(enabled(profile), List.of());
    }

    /** At most one bounded account-key lookup; caller must label this JSON as untrusted background. */
    public String recall(int owner, String query) {
        if (owner <= 0) return ""; // Compatibility chat overloads must not create a shared anonymous memory.
        requireOwner(owner);
        AiMemoryProfile profile = memories.find(owner);
        if (profile == null || !enabled(profile)) return "";
        List<Item> entries = decode(profile);
        Set<String> words = terms(query == null ? "" : query.substring(0, Math.min(2000, query.length())));
        record Candidate(Item item, int score, int recency) { }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Item item = entries.get(i);
            String text = item.content().toLowerCase(Locale.ROOT);
            int score = (int) words.stream().filter(text::contains).count();
            candidates.add(new Candidate(item, score, i));
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::recency).reversed()));
        List<Item> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (selected.size() >= 6) break;
            List<Item> next = new ArrayList<>(selected);
            next.add(candidate.item());
            if (encode(next).length() <= MAX_RECALL_CHARS) selected = next;
        }
        return selected.isEmpty() ? "" : encode(selected);
    }

    private void requireOwner(int owner) {
        if (owner <= 0) throw new BizException("请先登录后管理长期记忆");
        users.requireLoginUser(owner);
    }

    private AiMemoryProfile writableProfile(int owner) {
        AiMemoryProfile profile = memories.find(owner);
        if (profile != null) return profile;
        try { memories.insertEmpty(owner); }
        catch (DuplicateKeyException concurrentCreation) { /* One concurrent first-use insert may win. */ }
        profile = memories.find(owner);
        if (profile == null) throw new BizException("记忆状态已变化，请刷新后重试");
        return profile;
    }

    private void persist(int owner, AiMemoryProfile previous, boolean enabled, List<Item> entries) {
        if (previous.getVersion() == null || previous.getVersion() < 0
                || memories.compareAndSet(owner, previous.getVersion(), enabled, encode(entries)) != 1) {
            // No rebase/retry: in-flight writes must not restore a concurrently cleared or disabled state.
            throw new BizException("记忆已在其他窗口更新，请刷新后重试");
        }
    }

    private static boolean enabled(AiMemoryProfile profile) {
        if (profile.getEnabled() == null) throw new BizException("记忆数据异常，请联系管理员");
        return profile.getEnabled();
    }

    private List<Item> decode(AiMemoryProfile profile) {
        String stored = profile.getEntriesJson();
        if (stored == null || stored.length() > MAX_JSON_CHARS) throw invalidStored();
        try {
            List<Item> entries = json.readValue(stored, ITEMS);
            if (entries == null || entries.size() > MAX_ENTRIES) throw invalidStored();
            Set<String> ids = new HashSet<>();
            for (Item item : entries) {
                if (item == null) throw invalidStored();
                validateId(item.id());
                if (!ids.add(item.id()) || !normalize(item.content()).equals(item.content())) throw invalidStored();
                Instant.parse(item.createdAt());
            }
            return List.copyOf(entries);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException | DateTimeException invalid) {
            throw invalidStored();
        }
    }

    private String encode(List<Item> entries) {
        try {
            String encoded = json.writeValueAsString(entries);
            if (encoded.length() > MAX_JSON_CHARS) throw invalidStored();
            return encoded;
        } catch (JsonProcessingException invalid) { throw invalidStored(); }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CHARS
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new BizException("记忆内容不能为空且不能超过 500 个字符");
        }
        String text = value.strip().replaceAll("\\s+", " ");
        if (PRIVATE_DATA.matcher(text).find()) throw new BizException("请勿在长期记忆中保存密码、验证码、密钥或令牌");
        if (PATH_OR_LINK.matcher(text).find()) throw new BizException("长期记忆仅保存偏好和背景，请勿保存文件链接或服务器路径");
        if (PRIVILEGE_COMMAND.matcher(text).find()) throw new BizException("长期记忆不能改变权限、系统规则或任务确认要求");
        return text;
    }

    private static void validateId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new BizException("记忆标识不正确"); }
    }

    private static Set<String> terms(String query) {
        Set<String> words = new LinkedHashSet<>();
        var matcher = QUERY_WORDS.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.matches("[\\p{IsHan}]+") && word.length() > 1) {
                for (int i = 0; i < word.length() - 1; i++) words.add(word.substring(i, i + 2));
            } else words.add(word);
        }
        return words;
    }

    private static State view(boolean enabled, List<Item> entries) {
        return new State(enabled, MAX_ENTRIES, MAX_CHARS, List.copyOf(entries));
    }

    private static BizException invalidStored() { return new BizException("记忆数据异常，请刷新后重试或联系管理员"); }

    /** Participates in the same transaction as account deletion; the FK also prevents orphan races. */
    @EventListener
    public void onUserDeleted(UserDeleteEvent event) { memories.deleteOwned(event.getId()); }
}
