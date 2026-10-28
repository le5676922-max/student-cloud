package io.studentcloud.assistant;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded local lexical ranking. Scores are relative relevance, never semantic confidence. */
public final class KnowledgeTextMatcher {
    public static final int MAX_QUERY_CHARS = 2000;
    private static final int MAX_TERMS = 64;
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9][a-z0-9_.:/-]{0,79}");
    private static final Pattern SPACE = Pattern.compile("[^\\p{L}\\p{N}_./:-]+");
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "do", "does",
            "did", "i", "my", "me", "we", "you", "it", "to", "of", "in", "on", "at", "and", "or", "for",
            "can", "could", "would", "should", "how", "what", "when", "where", "which", "why", "please", "explain");
    private static final List<String> CHINESE_FILLERS = List.of("请问", "请帮我", "帮我", "告诉我", "为什么", "怎么样",
            "怎么办", "怎样", "如何", "怎么", "什么", "哪些", "可以", "能否", "一下", "解释", "请", "有没有", "有哪些");

    private KnowledgeTextMatcher() { }

    public record Query(String phrase, List<String> terms) { }

    /** Analyze once for a candidate set. Callers validate length before invoking this bounded helper. */
    public static Query query(String value) {
        String normalized = normalize(value == null ? "" : value.substring(0, Math.min(value.length(), MAX_QUERY_CHARS)));
        String tokenSource = normalized;
        for (String filler : CHINESE_FILLERS) tokenSource = tokenSource.replace(filler, " ");
        var terms = new LinkedHashSet<String>();
        var words = WORDS.matcher(tokenSource);
        while (words.find() && terms.size() < MAX_TERMS) {
            String word = words.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                if (points.length >= 2 && points.length <= 4) terms.add(word);
                for (int index = 0; index < points.length - 1 && terms.size() < MAX_TERMS; index++) {
                    String term = new String(points, index, 2);
                    if (!CHINESE_FILLERS.contains(term)) terms.add(term);
                }
            } else if (!STOP_WORDS.contains(word)) terms.add(word);
        }
        return new Query(compact(normalized), List.copyOf(terms));
    }

    /** Title matches outweigh incidental body mentions; repeated words never inflate a score. */
    public static double score(Query query, String title, String content) {
        if (query == null || query.phrase().isBlank() || query.terms().isEmpty()) return 0;
        String heading = normalize(title == null ? "" : title);
        String body = normalize(content == null ? "" : content);
        int titleMatches = 0, bodyMatches = 0, matched = 0;
        for (String term : query.terms()) {
            boolean inTitle = contains(heading, term), inBody = contains(body, term);
            if (inTitle) titleMatches++;
            if (inBody) bodyMatches++;
            if (inTitle || inBody) matched++;
        }
        boolean titlePhrase = compact(heading).contains(query.phrase());
        boolean bodyPhrase = compact(body).contains(query.phrase());
        // A single incidental bigram in a long natural-language question is too weak by itself.
        if (matched == 0 || titleMatches == 0 && !bodyPhrase && matched < Math.min(2, query.terms().size())) return 0;
        return titleMatches * 8d + bodyMatches * 2d + 12d * matched / query.terms().size()
                + (titlePhrase ? 40d : 0d) + (bodyPhrase ? 12d : 0d);
    }

    private static boolean contains(String text, String term) {
        if (Character.UnicodeScript.of(term.codePointAt(0)) == Character.UnicodeScript.HAN) return text.contains(term);
        for (int position = text.indexOf(term); position >= 0; position = text.indexOf(term, position + 1)) {
            int end = position + term.length();
            if ((position == 0 || !wordCharacter(text.charAt(position - 1)))
                    && (end == text.length() || !wordCharacter(text.charAt(end)))) return true;
        }
        return false;
    }

    private static boolean wordCharacter(char value) { return value < 128 && (Character.isLetterOrDigit(value) || value == '_'); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static String compact(String value) { return SPACE.matcher(value).replaceAll(""); }
}
