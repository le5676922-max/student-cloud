package io.studentcloud.intelligence.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.assistant.KnowledgeTextMatcher;
import io.studentcloud.common.ApplicationFault;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned public product help; contains no user files, private feedback or runtime secrets. */
public final class ProjectKnowledgeService {
    private final List<Entry> entries;

    public ProjectKnowledgeService() {
        try (var source = ProjectKnowledgeService.class.getResourceAsStream("/ai/knowledge/project-help.json")) {
            if (source == null) throw new IllegalStateException("Bundled project help is missing");
            Catalogue catalogue = new ObjectMapper().readValue(source, Catalogue.class);
            if (catalogue.schemaVersion() != 1 || catalogue.entries() == null
                    || catalogue.entries().size() < 20 || catalogue.entries().size() > 200)
                throw new IllegalStateException("Bundled project help has an unsupported schema or entry count");
            Set<String> ids = new HashSet<>();
            for (Entry entry : catalogue.entries()) {
                if (entry == null || entry.id() == null || !entry.id().matches("[a-z][a-z0-9-]{2,79}") || !ids.add(entry.id())
                        || !valid(entry.title(), 160) || !valid(entry.content(), 5000) || !valid(entry.source(), 500)
                        || !entry.source().startsWith("backend-independent/")
                        || entry.roles() == null || entry.roles().isEmpty() || entry.roles().contains(null)
                        || entry.tags() == null || entry.tags().size() > 24 || entry.tags().stream().anyMatch(tag -> !valid(tag, 80)))
                    throw new IllegalStateException("Bundled project help contains an invalid entry");
            }
            entries = List.copyOf(catalogue.entries());
        } catch (IOException failure) { throw new IllegalStateException("Cannot read bundled project help", failure); }
    }

    /** Actor must come from server authentication; this class does not replace AccountDirectory checks. */
    public List<Hit> search(Actor actor, String query, int limit) {
        if (actor == null || actor.id() < 1 || actor.role() == null)
            throw new ApplicationFault("42000", 401, "请先登录后使用项目帮助");
        if (query == null || query.isBlank() || query.length() > KnowledgeTextMatcher.MAX_QUERY_CHARS
                || query.chars().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')
                || limit < 1 || limit > 20)
            throw new ApplicationFault("41000", 400, "项目帮助问题须在2000字符以内，检索数量须在1到20之间");
        var terms = KnowledgeTextMatcher.query(query.trim());
        return entries.stream().filter(entry -> entry.roles().contains(actor.role()))
                .map(entry -> new Hit(entry.id(), entry.title(), entry.content(), entry.source(),
                        KnowledgeTextMatcher.score(terms, entry.title() + " " + String.join(" ", entry.tags()), entry.content())))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id))
                .limit(limit).toList();
    }

    public record Hit(String id, String title, String content, String source, double score) { }
    private record Catalogue(int schemaVersion, String revision, List<Entry> entries) { }
    private record Entry(String id, String title, String content, String source, List<Role> roles, List<String> tags) { }
    private static boolean valid(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
}
